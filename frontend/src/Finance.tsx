import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api, whatsappLink, type CardMachine, type Debtor, type FinanceSummary, type Payment, type PixSettings,
  type Receivable } from './api';
import { today } from './dates';
import { addMonths, brl, chargeMessage, dateBr, methodLabel, monthLabel, monthRange, moneyInput, parseMoney } from './finance';
import { PaymentSheet } from './PaymentSheet';
import { navigate } from './router';
import { Avatar, EmptyState, Icon, Menu, PageHeader, Sheet, useUi } from './ui';

export type FinanceSection = 'resumo' | 'devendo' | 'pagamentos' | 'receber' | 'maquininhas';
const SECTIONS: { id: FinanceSection; label: string }[] = [
  { id: 'resumo', label: 'Resumo' },
  { id: 'devendo', label: 'Quem deve' },
  { id: 'pagamentos', label: 'Pagamentos' },
  { id: 'receber', label: 'A receber' },
  { id: 'maquininhas', label: 'Maquininhas' }
];

/**
 * Financeiro da clínica: resumo do mês, quem está devendo (com lembrete pelo WhatsApp), pagamentos (com estorno),
 * o que a maquininha ainda vai depositar e o cadastro das maquininhas com as taxas.
 */
export function FinanceTab({ owner, section, clinicName }: { owner: boolean; section: FinanceSection; clinicName: string }) {
  const { toast } = useUi();
  const [paying, setPaying] = useState<{ patientId?: string } | null>(null);
  const [version, setVersion] = useState(0);
  const go = (id: FinanceSection) => navigate('/painel/financeiro' + (id === 'resumo' ? '' : '/' + id));

  return (
    <section>
      <PageHeader title="Financeiro" subtitle="Quem pagou, quem deve e o que ainda vai cair na conta.">
        {owner && <button className="primary" onClick={() => setPaying({})}><Icon name="plus" size={18} /> Registrar pagamento</button>}
      </PageHeader>
      <div className="segmented wide scroll-x" role="tablist" aria-label="Seções do financeiro">
        {SECTIONS.map(s => (
          <button key={s.id} role="tab" aria-selected={s.id === section} className={s.id === section ? 'active' : ''}
                  onClick={() => go(s.id)}>{s.label}</button>
        ))}
      </div>

      {section === 'resumo' && <SummarySection key={version} onSection={go} />}
      {section === 'devendo' && <DebtorsSection key={version} owner={owner} clinicName={clinicName}
                                                onPay={patientId => setPaying({ patientId })} />}
      {section === 'pagamentos' && <PaymentsSection key={version} owner={owner} />}
      {section === 'receber' && <ReceivablesSection key={version} owner={owner} />}
      {section === 'maquininhas' && <MachinesSection owner={owner} />}

      {paying && (
        <PaymentSheet patientId={paying.patientId} onClose={() => setPaying(null)}
                      onSaved={() => { setPaying(null); toast('Pagamento registrado'); setVersion(v => v + 1); }} />
      )}
    </section>
  );
}

function currentMonth(): string {
  return today().slice(0, 7);
}

function MonthNav({ month, setMonth }: { month: string; setMonth: (m: string) => void }) {
  return (
    <div className="month-nav">
      <button className="icon-button" aria-label="Mês anterior" onClick={() => setMonth(addMonths(month, -1))}><Icon name="left" /></button>
      <strong>{monthLabel(month)}</strong>
      <button className="icon-button" aria-label="Próximo mês" disabled={month >= currentMonth()}
              onClick={() => setMonth(addMonths(month, 1))}><Icon name="right" /></button>
    </div>
  );
}

function SummarySection({ onSection }: { onSection: (s: FinanceSection) => void }) {
  const [month, setMonth] = useState(currentMonth());
  const [summary, setSummary] = useState<FinanceSummary | null>(null);
  const [error, setError] = useState('');
  useEffect(() => {
    api<FinanceSummary>(`/api/v1/finance/summary?month=${month}`).then(setSummary).catch(e => setError((e as Error).message));
  }, [month]);

  return (
    <div className="stack">
      <MonthNav month={month} setMonth={setMonth} />
      {error && <p className="error-box">{error}</p>}
      {summary && (
        <>
          <div className="money-stats">
            <div className="money-stat main"><span>Recebido no mês</span><strong>{brl(summary.received)}</strong>
              <small>{summary.payments} {summary.payments === 1 ? 'pagamento' : 'pagamentos'}</small></div>
            <div className="money-stat"><span>Taxas de cartão</span><strong>{brl(summary.fees)}</strong></div>
            <div className="money-stat"><span>Líquido</span><strong>{brl(summary.net)}</strong></div>
          </div>
          <div className="money-stats two">
            <button className="money-stat link" onClick={() => onSection('devendo')}>
              <span>Em aberto (hoje)</span><strong className={summary.open > 0 ? 'warn' : ''}>{brl(summary.open)}</strong>
              <small>Ver quem deve <Icon name="right" size={14} /></small></button>
            <button className="money-stat link" onClick={() => onSection('receber')}>
              <span>A cair da maquininha</span><strong>{brl(summary.toReceive)}</strong>
              <small>Ver depósitos <Icon name="right" size={14} /></small></button>
          </div>
          {summary.byMethod.length > 0 && (
            <div className="card">
              <h3>Por forma de pagamento</h3>
              <ul className="method-list">
                {summary.byMethod.map(m => {
                  const share = summary.received > 0 ? (m.charged / summary.received) * 100 : 0;
                  return (
                    <li key={m.method}>
                      <div className="method-head"><span>{methodLabel(m.method)}</span><strong>{brl(m.charged)}</strong></div>
                      <div className="meter" aria-hidden="true"><span style={{ width: `${share}%` }} /></div>
                      {m.fees > 0 && <p className="muted small">taxa {brl(m.fees)} · líquido {brl(m.net)}</p>}
                    </li>
                  );
                })}
              </ul>
            </div>
          )}
          {summary.payments === 0 && (
            <EmptyState icon="money" title="Nenhum pagamento neste mês"
                        text="Registre os pagamentos para ver quanto entrou, quanto ficou de taxa e quem está devendo." />
          )}
        </>
      )}
    </div>
  );
}

function DebtorsSection({ owner, clinicName, onPay }: { owner: boolean; clinicName: string; onPay: (patientId: string) => void }) {
  const [items, setItems] = useState<Debtor[] | null>(null);
  const [pix, setPix] = useState(false);
  const [charging, setCharging] = useState<Debtor | null>(null);
  const [error, setError] = useState('');
  useEffect(() => {
    api<Debtor[]>('/api/v1/finance/debtors').then(setItems).catch(e => setError((e as Error).message));
    api<PixSettings>('/api/v1/clinic/pix').then(s => setPix(!!s.keyType)).catch(() => undefined);
  }, []);
  const total = items?.reduce((sum, d) => sum + d.balance, 0) ?? 0;

  return (
    <div className="stack">
      {error && <p className="error-box">{error}</p>}
      {items && items.length > 0 && (
        <p className="muted">{items.length} {items.length === 1 ? 'paciente' : 'pacientes'} · <strong>{brl(total)}</strong> em aberto.
          {' '}Conta: pacotes vendidos com valor + sessões avulsas realizadas, menos os pagamentos.</p>
      )}
      {items?.length === 0 && (
        <EmptyState icon="check" title="Ninguém devendo"
                    text="Pacotes com valor e sessões avulsas realizadas entram aqui até serem pagos." />
      )}
      <ul className="cards">
        {items?.map(d => (
          <li key={d.patientId} className="row-card">
            <Avatar name={d.fullName} />
            <div className="grow">
              <a className="name-link" href={`/painel/pacientes/${d.patientId}`}
                 onClick={e => { e.preventDefault(); navigate('/painel/pacientes/' + d.patientId); }}>{d.fullName}</a>
              <div className="meta">
                <span>cobrado {brl(d.charged)}</span><span>pago {brl(d.paid)}</span>
                <span>{d.lastPaymentOn ? `último pagamento ${dateBr(d.lastPaymentOn)}` : 'nenhum pagamento'}</span>
              </div>
            </div>
            <strong className="amount warn">{brl(d.balance)}</strong>
            <div className="row-actions">
              {d.whatsapp && (pix
                ? <button className="button wa" onClick={() => setCharging(d)}><Icon name="whatsapp" size={18} /> Lembrar</button>
                : <a className="button wa" target="_blank" rel="noreferrer" href={whatsappLink(d.whatsapp, chargeMessage(d.fullName, d.balance, clinicName))}>
                    <Icon name="whatsapp" size={18} /> Lembrar</a>)}
              {owner && <button onClick={() => onPay(d.patientId)}>Recebi</button>}
            </div>
          </li>
        ))}
      </ul>
      {charging && <ChargeSheet debtor={charging} clinicName={clinicName} onClose={() => setCharging(null)} />}
    </div>
  );
}

/** Lembrete com Pix copia e cola do valor em aberto (a fisioterapeuta revisa e envia pelo WhatsApp). */
function ChargeSheet({ debtor, clinicName, onClose }: { debtor: Debtor; clinicName: string; onClose: () => void }) {
  const [code, setCode] = useState<{ code: string; qrSvg: string } | null>(null);
  const [withPix, setWithPix] = useState(true);
  const [error, setError] = useState('');
  useEffect(() => {
    api<{ code: string; qrSvg: string }>('/api/v1/finance/pix', 'POST', { amount: debtor.balance }).then(setCode)
      .catch(e => { setError((e as Error).message); setWithPix(false); });
  }, [debtor]);
  const message = chargeMessage(debtor.fullName, debtor.balance, clinicName, withPix && code ? code.code : undefined);
  return (
    <Sheet title={`Lembrar ${debtor.fullName.split(' ')[0]}`} onClose={onClose}>
      <div className="form">
        <p className="muted small">Em aberto: <strong>{brl(debtor.balance)}</strong>. Revise a mensagem e envie pelo WhatsApp.</p>
        {code && (
          <label className="switch small"><input type="checkbox" checked={withPix} onChange={e => setWithPix(e.target.checked)} />
            <span>Incluir Pix copia e cola com o valor</span></label>
        )}
        {error && <p className="muted small">{error}</p>}
        <pre className="message-preview">{message}</pre>
        <a className="button wa big" target="_blank" rel="noreferrer" onClick={onClose}
           href={whatsappLink(debtor.whatsapp ?? '', message)}><Icon name="whatsapp" size={20} /> Enviar no WhatsApp</a>
      </div>
    </Sheet>
  );
}

function PaymentsSection({ owner }: { owner: boolean }) {
  const { toast, confirm } = useUi();
  const [month, setMonth] = useState(currentMonth());
  const [items, setItems] = useState<Payment[] | null>(null);
  const [error, setError] = useState('');
  const load = useCallback(() => {
    const [from, to] = monthRange(month);
    return api<Payment[]>(`/api/v1/payments?from=${from}&to=${to}`).then(setItems).catch(e => setError((e as Error).message));
  }, [month]);
  useEffect(() => { load(); }, [load]);

  async function cancel(p: Payment) {
    if (!await confirm(`Estornar o pagamento de ${brl(p.amount)} de ${p.patientName}? Ele continua no histórico, mas volta a contar como em aberto.`,
      { title: 'Estornar pagamento', confirmLabel: 'Estornar', danger: true })) return;
    try {
      await api(`/api/v1/payments/${p.id}/cancel`, 'POST');
      toast('Pagamento estornado');
      await load();
    } catch (e) { setError((e as Error).message); }
  }

  return (
    <div className="stack">
      <MonthNav month={month} setMonth={setMonth} />
      {error && <p className="error-box">{error}</p>}
      {items?.length === 0 && <EmptyState icon="money" title="Nenhum pagamento neste mês" />}
      <ul className="cards">
        {items?.map(p => <PaymentRow key={p.id} payment={p} showPatient onCancel={owner && !p.canceled ? () => cancel(p) : undefined} />)}
      </ul>
    </div>
  );
}

/** Linha de pagamento (lista do mês e conta do paciente). */
export function PaymentRow({ payment: p, showPatient, onCancel }: { payment: Payment; showPatient?: boolean; onCancel?: () => void }) {
  const what = p.packageName ? `Pacote ${p.packageName}` : p.appointmentDate ? `Sessão de ${dateBr(p.appointmentDate)}` : 'Por conta';
  return (
    <li className={p.canceled ? 'row-card inactive' : 'row-card'}>
      <div className="pay-date"><strong>{p.paidOn.slice(8, 10)}</strong><span>{dateBr(p.paidOn).slice(3, 5)}</span></div>
      <div className="grow">
        <strong>{showPatient ? p.patientName : what}</strong>
        {p.canceled && <span className="pill">estornado</span>}
        <div className="meta">
          {showPatient && <span>{what}</span>}
          <span>{methodLabel(p.method, p.installments, p.machineName)}</span>
          {p.feeAmount > 0 && <span>taxa {brl(p.feeAmount)} · líquido {brl(p.netAmount)}</span>}
          {p.chargedAmount > p.amount && <span>cobrado {brl(p.chargedAmount)}</span>}
        </div>
        {p.notes && <p className="appt-notes">{p.notes}</p>}
      </div>
      <strong className="amount">{brl(p.amount)}</strong>
      <Menu label={`Ações do pagamento de ${p.patientName}`} items={[
        !p.canceled && { label: 'Recibo (imprimir/PDF)', icon: 'external', href: `/painel/recibo/pagamento/${p.id}` },
        onCancel && { label: 'Estornar', icon: 'repeat', danger: true, onClick: onCancel }
      ]} />
    </li>
  );
}

function ReceivablesSection({ owner }: { owner: boolean }) {
  const { toast } = useUi();
  const [all, setAll] = useState(false);
  const [items, setItems] = useState<Receivable[] | null>(null);
  const [error, setError] = useState('');
  const load = useCallback(() => api<Receivable[]>(`/api/v1/finance/receivables?includeReceived=${all}`).then(setItems)
    .catch(e => setError((e as Error).message)), [all]);
  useEffect(() => { load(); }, [load]);

  async function mark(r: Receivable, received: boolean) {
    try {
      await api(`/api/v1/finance/receivables/${r.id}/received?received=${received}`, 'POST');
      toast(received ? 'Marcado como recebido' : 'Desmarcado');
      await load();
    } catch (e) { setError((e as Error).message); }
  }

  const pending = items?.filter(r => !r.receivedOn) ?? [];
  const late = pending.filter(r => r.dueOn < today());
  return (
    <div className="stack">
      <p className="muted">O que a maquininha ainda vai depositar. Confira no extrato e marque como recebido.</p>
      <label className="switch small"><input type="checkbox" checked={all} onChange={e => setAll(e.target.checked)} />
        <span>Mostrar os já recebidos</span></label>
      {pending.length > 0 && (
        <p className="notice small">A cair: <strong>{brl(pending.reduce((s, r) => s + r.amount, 0))}</strong>
          {late.length > 0 && <> · {late.length} com data passada (confira no extrato)</>}</p>
      )}
      {error && <p className="error-box">{error}</p>}
      {items?.length === 0 && <EmptyState icon="money" title="Nada a receber" text="Pagamentos no cartão geram os depósitos previstos aqui." />}
      <ul className="cards">
        {items?.map(r => (
          <li key={r.id} className={r.receivedOn ? 'row-card inactive' : 'row-card'}>
            <div className="pay-date"><strong>{r.dueOn.slice(8, 10)}</strong><span>{dateBr(r.dueOn).slice(3, 5)}</span></div>
            <div className="grow">
              <strong>{r.patientName}</strong>
              {!r.receivedOn && r.dueOn < today() && <span className="pill warn">data passada</span>}
              <div className="meta">
                {r.machineName && <span>{r.machineName}</span>}
                {r.installments > 1 && <span>parcela {r.installment} de {r.installments}</span>}
                {r.receivedOn && <span>recebido em {dateBr(r.receivedOn)}</span>}
              </div>
            </div>
            <strong className="amount">{brl(r.amount)}</strong>
            {owner && (r.receivedOn
              ? <button className="link-button" onClick={() => mark(r, false)}>Desfazer</button>
              : <button onClick={() => mark(r, true)}><Icon name="check" size={18} /> Recebido</button>)}
          </li>
        ))}
      </ul>
    </div>
  );
}

type MachineForm = {
  id?: string; name: string; debitFee: string; creditFee: string; fixedFee: string; debitDays: string; creditDays: string;
  anticipated: boolean; fees: Record<number, string>;
};
const EMPTY_MACHINE: MachineForm = {
  name: '', debitFee: '', creditFee: '', fixedFee: '', debitDays: '1', creditDays: '30', anticipated: false, fees: {}
};
const pct = (n: number) => String(n).replace('.', ',');

function MachinesSection({ owner }: { owner: boolean }) {
  const { toast, confirm } = useUi();
  const [items, setItems] = useState<CardMachine[] | null>(null);
  const [editing, setEditing] = useState<MachineForm | null>(null);
  const [error, setError] = useState('');
  const load = useCallback(() => api<CardMachine[]>('/api/v1/card-machines').then(setItems).catch(e => setError((e as Error).message)), []);
  useEffect(() => { load(); }, [load]);

  function edit(m: CardMachine) {
    setEditing({
      id: m.id, name: m.name, debitFee: pct(m.debitFee), creditFee: pct(m.creditFee), fixedFee: m.fixedFee ? moneyInput(m.fixedFee) : '',
      debitDays: String(m.debitDays), creditDays: String(m.creditDays), anticipated: m.anticipated,
      fees: Object.fromEntries(m.installmentFees.map(f => [f.installments, pct(f.fee)]))
    });
  }
  async function deactivate(m: CardMachine) {
    if (!await confirm(`Desativar a maquininha ${m.name}? Os pagamentos já feitos com ela continuam no histórico.`,
      { confirmLabel: 'Desativar', danger: true })) return;
    try {
      await api(`/api/v1/card-machines/${m.id}`, 'DELETE');
      toast('Maquininha desativada');
      await load();
    } catch (e) { setError((e as Error).message); }
  }

  return (
    <div className="stack">
      <div className="section-title">
        <p className="muted">Cadastre as taxas da sua maquininha (estão no app dela) para o sistema calcular o que cai na conta.</p>
        {owner && <button className="primary" onClick={() => setEditing({ ...EMPTY_MACHINE })}><Icon name="plus" size={18} /> Nova maquininha</button>}
      </div>
      {error && <p className="error-box">{error}</p>}
      {items?.length === 0 && <EmptyState icon="money" title="Nenhuma maquininha" text="Ex.: Stone, Ton, InfinitePay, Mercado Pago, PagBank." />}
      <ul className="cards">
        {items?.map(m => (
          <li key={m.id} className={m.active ? 'row-card' : 'row-card inactive'}>
            <span className="stat-icon"><Icon name="money" /></span>
            <div className="grow">
              <strong>{m.name}</strong>{!m.active && <span className="pill">desativada</span>}
              <div className="meta">
                <span>débito {pct(m.debitFee)}%</span>
                <span>crédito {pct(m.creditFee)}%</span>
                {m.installmentFees.length > 0 && <span>parcelado {pct(m.installmentFees[0].fee)}% a {pct(m.installmentFees[m.installmentFees.length - 1].fee)}%</span>}
                {m.fixedFee > 0 && <span>+ {brl(m.fixedFee)} por venda</span>}
                <span>{m.anticipated ? 'recebe tudo antecipado' : `crédito cai em ${m.creditDays} dias`}</span>
              </div>
            </div>
            {owner && m.active && <Menu label={`Ações da maquininha ${m.name}`} items={[
              { label: 'Editar taxas', icon: 'edit', onClick: () => edit(m) },
              { label: 'Desativar', icon: 'trash', danger: true, onClick: () => deactivate(m) }
            ]} />}
          </li>
        ))}
      </ul>
      {editing && <MachineSheet form={editing} setForm={setEditing} onSaved={async () => { setEditing(null); toast('Maquininha salva'); await load(); }} />}
    </div>
  );
}

function MachineSheet({ form, setForm, onSaved }: { form: MachineForm; setForm: (f: MachineForm | null) => void; onSaved: () => void }) {
  const [error, setError] = useState('');
  const [showInstallments, setShowInstallments] = useState(Object.keys(form.fees).length > 0);
  const set = (key: keyof MachineForm, value: string | boolean) => setForm({ ...form, [key]: value });

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError('');
    const percent = (text: string, label: string) => {
      const v = parseMoney(text || '0');
      if (v === null || Number.isNaN(v) || v > 30) throw new Error(`${label}: use um número entre 0 e 30. Ex.: 3,49`);
      return v;
    };
    try {
      const installmentFees = Object.entries(form.fees).filter(([, v]) => v.trim())
        .map(([n, v]) => ({ installments: Number(n), fee: percent(v, `Taxa em ${n}x`) }));
      const payload = {
        name: form.name, debitFee: percent(form.debitFee, 'Débito'), creditFee: percent(form.creditFee, 'Crédito à vista'),
        fixedFee: form.fixedFee ? percent(form.fixedFee, 'Tarifa fixa') : 0,
        debitDays: Number(form.debitDays || 1), creditDays: Number(form.creditDays || 30), anticipated: form.anticipated, installmentFees
      };
      if (form.id) await api(`/api/v1/card-machines/${form.id}`, 'PUT', payload);
      else await api('/api/v1/card-machines', 'POST', payload);
      onSaved();
    } catch (e) { setError((e as Error).message); }
  }

  return (
    <Sheet title={form.id ? 'Editar maquininha' : 'Nova maquininha'} onClose={() => setForm(null)}>
      <form className="form" onSubmit={submit}>
        <label>Nome<input required maxLength={60} placeholder="Ex.: Stone" value={form.name} onChange={e => set('name', e.target.value)} /></label>
        <div className="row">
          <label>Débito (%)<input inputMode="decimal" required placeholder="1,99" value={form.debitFee} onChange={e => set('debitFee', e.target.value)} /></label>
          <label>Crédito à vista (%)<input inputMode="decimal" required placeholder="3,49" value={form.creditFee} onChange={e => set('creditFee', e.target.value)} /></label>
        </div>
        <div className="row">
          <label>Débito cai em (dias)<input type="number" min={0} max={60} value={form.debitDays} onChange={e => set('debitDays', e.target.value)} /></label>
          <label>Crédito cai em (dias)<input type="number" min={0} max={60} value={form.creditDays} onChange={e => set('creditDays', e.target.value)} /></label>
        </div>
        <label>Tarifa fixa por venda (R$, opcional)<input inputMode="decimal" placeholder="0,00" value={form.fixedFee}
                                                          onChange={e => set('fixedFee', e.target.value)} /></label>
        <label className="switch"><input type="checkbox" checked={form.anticipated} onChange={e => set('anticipated', e.target.checked)} />
          <span>Recebo tudo antecipado (parcelado cai de uma vez)</span></label>
        {!showInstallments
          ? <button type="button" className="link-button" onClick={() => setShowInstallments(true)}>+ Taxas do parcelado (2x a 12x)</button>
          : (
            <fieldset className="field">
              <legend>Crédito parcelado (%) — em branco usa a taxa do crédito à vista</legend>
              <div className="installment-grid">
                {Array.from({ length: 11 }, (_, i) => i + 2).map(n => (
                  <label key={n}>{n}x<input inputMode="decimal" value={form.fees[n] ?? ''} aria-label={`Taxa em ${n}x`}
                                            onChange={e => setForm({ ...form, fees: { ...form.fees, [n]: e.target.value } })} /></label>
                ))}
              </div>
            </fieldset>
          )}
        {error && <p className="error-box" role="alert">{error}</p>}
        <div className="sheet-actions">
          <button type="button" onClick={() => setForm(null)}>Cancelar</button>
          <button className="primary">Salvar</button>
        </div>
      </form>
    </Sheet>
  );
}
