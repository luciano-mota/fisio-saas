import { useEffect, useState, type FormEvent } from 'react';
import { api, type CardMachine, type Charge, type Patient, type PatientFinance, type Payment, type PaymentPreview } from './api';
import { today } from './dates';
import { brl, dateBr, isCard, METHODS, moneyInput, parseMoney, type Method } from './finance';
import { Sheet } from './ui';

/** O que o pagamento quita: um pacote, uma sessão avulsa ou nada específico ("por conta"). */
export type PaymentTarget = { kind: Charge['kind']; id: string; label?: string; amount?: number | null };

/**
 * Registrar pagamento. A prévia (taxa, quanto cobrar e o que cai na conta) vem do servidor, com as mesmas regras do
 * registro. Sem paciente escolhido, mostra a lista de pacientes.
 */
export function PaymentSheet({ patientId: fixedPatient, target, onClose, onSaved }: {
  patientId?: string; target?: PaymentTarget; onClose: () => void; onSaved: (payment: Payment) => void;
}) {
  const [patients, setPatients] = useState<Patient[]>([]);
  const [patientId, setPatientId] = useState(fixedPatient ?? '');
  const [finance, setFinance] = useState<PatientFinance | null>(null);
  const [machines, setMachines] = useState<CardMachine[]>([]);
  const wanted = target ? `${target.kind}:${target.id}` : '';
  const [targetKey, setTargetKey] = useState(wanted);
  const [amount, setAmount] = useState('');
  const [method, setMethod] = useState<Method>('PIX');
  const [machineId, setMachineId] = useState('');
  const [installments, setInstallments] = useState(1);
  const [settlementDays, setSettlementDays] = useState<number | null>(null);
  const [paidOn, setPaidOn] = useState(today());
  const [notes, setNotes] = useState('');
  const [preview, setPreview] = useState<PaymentPreview | null>(null);
  const [previewError, setPreviewError] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api<CardMachine[]>('/api/v1/card-machines').then(list => {
      const active = list.filter(m => m.active);
      setMachines(active);
      if (active.length > 0) setMachineId(active[0].id);
    }).catch(() => undefined);
    if (!fixedPatient) api<Patient[]>('/api/v1/patients').then(setPatients).catch(() => undefined);
  }, [fixedPatient]);

  // Ao trocar de paciente: carrega o que ele deve e sugere o item em aberto (o escolhido ou o mais antigo).
  useEffect(() => {
    setFinance(null);
    if (!patientId) return;
    api<PatientFinance>(`/api/v1/patients/${patientId}/finance`).then(f => {
      setFinance(f);
      const open = f.charges.filter(c => c.value > c.paid);
      const chosen = wanted ? f.charges.find(c => `${c.kind}:${c.id}` === wanted) : open[open.length - 1];
      if (chosen) {
        setTargetKey(`${chosen.kind}:${chosen.id}`);
        setAmount(moneyInput(Math.max(0, chosen.value - chosen.paid) || chosen.value));
      } else if (wanted && target?.label) {
        // Sessão ainda não realizada (pagamento adiantado): não está nas cobranças, mas pode ser vinculada.
        setTargetKey(wanted);
        setAmount(moneyInput(target.amount ?? null));
      } else {
        setTargetKey('');
        setAmount(f.balance > 0 ? moneyInput(f.balance) : '');
      }
    }).catch(e => setError((e as Error).message));
  }, [patientId, wanted]);

  const value = parseMoney(amount);
  const card = isCard(method);
  const selectedMachine = machines.find(m => m.id === machineId);
  const supportedInstallments = [1, ...(selectedMachine?.installmentFees.map(f => f.installments) ?? [])];
  const validInstallments = supportedInstallments.includes(installments) ? installments : 1;
  const defaultDays = selectedMachine?.creditDays;
  const selectedTerm = selectedMachine?.creditTerms.find(t => t.days === settlementDays)
    ?? selectedMachine?.creditTerms.find(t => t.days === defaultDays)
    ?? selectedMachine?.creditTerms[0];
  const valid = value !== null && Number.isFinite(value) && value > 0;

  // Prévia ao vivo (com uma pequena espera para não chamar a cada tecla).
  useEffect(() => {
    setPreview(null);
    setPreviewError('');
    if (!valid || (card && !machineId)) return;
    // Evita que uma prévia antiga sobrescreva os valores quando prazo, maquininha ou parcelas mudam rapidamente.
    let active = true;
    const timer = setTimeout(() => {
      api<PaymentPreview>('/api/v1/payments/preview', 'POST', {
        method, machineId: card ? machineId : null, installments: method === 'CREDITO' ? validInstallments : 1,
        settlementDays: method === 'CREDITO' && validInstallments === 1 ? selectedTerm?.days : null,
        amount: value, paidOn
      }).then(result => { if (active) setPreview(result); })
        .catch(e => { if (active) setPreviewError((e as Error).message); });
    }, 250);
    return () => { active = false; clearTimeout(timer); };
  }, [value, valid, method, machineId, validInstallments, selectedTerm?.days, paidOn, card]);

  function chooseTarget(key: string) {
    setTargetKey(key);
    const charge = finance?.charges.find(c => `${c.kind}:${c.id}` === key);
    if (charge) setAmount(moneyInput(Math.max(0, charge.value - charge.paid) || charge.value));
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError('');
    if (!valid) { setError('Informe o valor. Ex.: 90,00'); return; }
    const [kind, id] = targetKey ? targetKey.split(':') : ['', ''];
    setBusy(true);
    try {
      const saved = await api<Payment>('/api/v1/payments', 'POST', {
        patientId, patientPackageId: kind === 'PACOTE' ? id : null, appointmentId: kind === 'SESSAO' ? id : null,
        method, machineId: card ? machineId : null, installments: method === 'CREDITO' ? validInstallments : 1,
        settlementDays: method === 'CREDITO' && validInstallments === 1 ? selectedTerm?.days : null,
        amount: value, paidOn, notes: notes || null
      });
      onSaved(saved);
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }

  const passed = preview && preview.chargedAmount > (value ?? 0);
  const open = finance?.charges.filter(c => c.value > c.paid || `${c.kind}:${c.id}` === targetKey) ?? [];

  return (
    <Sheet title="Registrar pagamento" onClose={onClose}>
      <form onInput={() => setError('')} className="form" onSubmit={submit}>
        {!fixedPatient && (
          <label>Paciente
            <select required value={patientId} onChange={e => setPatientId(e.target.value)}>
              <option value="">Escolha…</option>
              {patients.map(p => <option key={p.id} value={p.id}>{p.fullName}</option>)}
            </select>
          </label>
        )}
        {finance && finance.balance > 0 && (
          <p className="notice small">Em aberto: <strong>{brl(finance.balance)}</strong></p>
        )}
        {patientId && (
          <label>Referente a
            <select value={targetKey} onChange={e => chooseTarget(e.target.value)}>
              <option value="">Por conta (sem item específico)</option>
              {target?.label && !finance?.charges.some(c => `${c.kind}:${c.id}` === wanted) &&
                <option value={wanted}>{target.label}</option>}
              {open.map(c => (
                <option key={c.kind + c.id} value={`${c.kind}:${c.id}`}>
                  {c.description} · {dateBr(c.date)} — {c.paid > 0 ? `falta ${brl(Math.max(0, c.value - c.paid))}` : brl(c.value)}
                </option>
              ))}
            </select>
          </label>
        )}

        <div className="row">
          <label>Valor<input inputMode="decimal" required placeholder="Ex.: 90,00" value={amount}
                             onChange={e => setAmount(e.target.value)} /></label>
          <label>Data<input type="date" required value={paidOn} onChange={e => setPaidOn(e.target.value)} /></label>
        </div>

        <fieldset className="field">
          <legend>Forma de pagamento</legend>
          <div className="chips" role="radiogroup" aria-label="Forma de pagamento">
            {METHODS.map(m => (
              <button key={m.id} type="button" role="radio" aria-checked={method === m.id}
                      className={method === m.id ? 'chip on' : 'chip'}
                      onClick={() => { setMethod(m.id); if (m.id !== 'CREDITO') setInstallments(1); }}>{m.label}</button>
            ))}
          </div>
        </fieldset>

        {card && machines.length === 0 && (
          <p className="notice small">Cadastre sua maquininha em Financeiro → Maquininhas para calcular a taxa.</p>
        )}
        {card && machines.length > 0 && (
          <div className="row">
            <label>Maquininha
              <select value={machineId} onChange={e => setMachineId(e.target.value)}>
                {machines.map(m => <option key={m.id} value={m.id}>{m.name}</option>)}
              </select>
            </label>
            {method === 'CREDITO' && (
              <label>Parcelas
                <select value={validInstallments} onChange={e => setInstallments(Number(e.target.value))}>
                  {supportedInstallments.map(n => <option key={n} value={n}>{n === 1 ? 'À vista' : `${n}x`}</option>)}
                </select>
              </label>
            )}
          </div>
        )}

        {card && method === 'CREDITO' && validInstallments === 1 && selectedMachine && (
          <label>Quando receber o crédito à vista
            <select value={selectedTerm?.days ?? ''} onChange={e => setSettlementDays(Number(e.target.value))}>
              {selectedMachine.creditTerms.map(t => (
                <option key={t.days} value={t.days}>
                  {t.days === 0 ? 'Na hora' : `Em ${t.days} dias`} · taxa {String(t.fee).replace('.', ',')}%
                </option>
              ))}
            </select>
          </label>
        )}

        {preview && card && (
          <div className="preview-box" aria-live="polite">
            {passed && <div className="preview-line strong"><span>Cobrar na maquininha</span><strong>{brl(preview.chargedAmount)}</strong></div>}
            <div className="preview-line"><span>Taxa ({String(preview.feePercent).replace('.', ',')}%)</span><span>− {brl(preview.feeAmount)}</span></div>
            <div className="preview-line strong"><span>Cai na sua conta</span><strong>{brl(preview.netAmount)}</strong></div>
            {preview.receivables.length > 0 && (
              <p className="muted small">
                {preview.receivables.length === 1
                  ? `Previsto para ${dateBr(preview.receivables[0].dueOn)}.`
                  : `${preview.receivables.length} depósitos de ${brl(preview.receivables[preview.receivables.length - 1].amount)}, ` +
                    `de ${dateBr(preview.receivables[0].dueOn)} a ${dateBr(preview.receivables[preview.receivables.length - 1].dueOn)}.`}
              </p>
            )}
            {passed && <p className="muted small">Taxa repassada ao paciente (Clínica → Pagamentos).</p>}
          </div>
        )}
        {previewError && <p className="error small">{previewError}</p>}

        <label>Observação (opcional)<input maxLength={300} value={notes} placeholder="Ex.: pagou metade, resto dia 30"
                                           onChange={e => setNotes(e.target.value)} /></label>
        {error && <p className="error-box" role="alert">{error}</p>}
        <div className="sheet-actions">
          <button type="button" onClick={onClose}>Cancelar</button>
          <button className="primary" disabled={busy || !patientId || (card && (!selectedMachine ||
            (method === 'CREDITO' && validInstallments === 1 && !selectedTerm)))}>Registrar pagamento</button>
        </div>
      </form>
    </Sheet>
  );
}
