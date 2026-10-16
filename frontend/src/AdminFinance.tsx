import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api, type AdminFinance as Finance, type PixKeyType, type SubscriptionCharge, type SubscriptionPlan } from './api';
import { addMonths, brl, dateBr, monthLabel, moneyInput, parseMoney } from './finance';
import { maskCpf, maskPhone } from './format';
import { TYPES } from './PixSettings';
import { adminStatusLabel, statusTone } from './subscription';
import { EmptyState, Icon, Sheet, useUi } from './ui';

const PAY_METHODS = [['PIX', 'Pix'], ['CARTAO', 'Cartão'], ['BOLETO', 'Boleto'], ['DINHEIRO', 'Dinheiro'], ['OUTRO', 'Outro']] as const;

/** Financeiro da plataforma: mensalidades dos assinantes, pagamentos informados para confirmar e atrasadas. */
export function AdminFinance() {
  const { toast, confirm } = useUi();
  const [month, setMonth] = useState('');
  const [data, setData] = useState<Finance | null>(null);
  const [error, setError] = useState('');
  const [confirming, setConfirming] = useState<SubscriptionCharge | null>(null);
  const [reasonFor, setReasonFor] = useState<{ charge: SubscriptionCharge; kind: 'reject' | 'cancel' } | null>(null);
  const [pixOpen, setPixOpen] = useState(false);

  const load = useCallback((m: string) => api<Finance>(`/api/v1/admin/finance?month=${m}`)
    .then(d => { setData(d); setMonth(d.month); }).catch(e => setError((e as Error).message)), []);
  // Primeiro carregamento usa o mês de hoje no horário do Brasil (vem do servidor).
  useEffect(() => { api<{ today: string }>('/api/v1/admin/clinics').then(o => load(o.today.slice(0, 7))).catch(e => setError((e as Error).message)); }, [load]);

  async function generate() {
    if (!await confirm(`Gerar as mensalidades de ${monthLabel(month)} para as clínicas assinantes com valor de plano? Quem já tem a do mês não recebe outra.`,
      { confirmLabel: 'Gerar mensalidades' })) return;
    try {
      const r = await api<{ created: number; skipped: number }>(`/api/v1/admin/finance/generate?month=${month}`, 'POST');
      toast(r.created === 0 ? 'Nenhuma mensalidade nova' : `${r.created} ${r.created === 1 ? 'mensalidade gerada' : 'mensalidades geradas'}`);
      load(month);
    } catch (e) { setError((e as Error).message); }
  }

  const stats = data && [
    ['Receita mensal prevista', brl(data.mrr), 'soma dos planos ativos', ''],
    ['Recebido no mês', brl(data.received.amount), `${data.received.count} confirmadas`, 'ok'],
    ['Pagamento informado', brl(data.informed.amount), `${data.informed.count} para confirmar`, data.informed.count ? 'info' : ''],
    ['Em aberto', brl(data.open.amount), `${data.open.count} mensalidades`, ''],
    ['Atrasadas', brl(data.overdue.amount), `${data.overdue.count} mensalidades`, data.overdue.count ? 'danger' : '']
  ];

  return (
    <>
      <div className="admin-head">
        <div><h1>Financeiro dos assinantes</h1>
          <p className="muted">Mensalidades das clínicas. O pagamento informado pela clínica fica pendente até você confirmar.</p></div>
        <div className="actions">
          <button onClick={() => setPixOpen(true)}><Icon name="key" size={18} /> Meu Pix</button>
          <button className="primary" disabled={!month} onClick={generate}><Icon name="plus" size={18} /> Gerar mensalidades do mês</button>
        </div>
      </div>
      {error && <p className="error-box" role="alert">{error}</p>}
      {month && (
        <div className="month-nav">
          <button className="icon-button" aria-label="Mês anterior" onClick={() => load(addMonths(month, -1))}><Icon name="left" /></button>
          <strong>{monthLabel(month)}</strong>
          <button className="icon-button" aria-label="Próximo mês" onClick={() => load(addMonths(month, 1))}><Icon name="right" /></button>
        </div>
      )}
      {stats && (
        <div className="admin-tiles">
          {stats.map(([label, value, hint, tone]) => (
            <div key={label} className={`admin-tile money ${tone}`}><span>{label}</span><strong>{value}</strong><small>{hint}</small></div>
          ))}
        </div>
      )}
      {data?.charges.length === 0 && <EmptyState icon="money" title="Nenhuma mensalidade neste mês"
                                                  text="Defina o valor do plano na ficha de cada clínica e toque em Gerar mensalidades do mês." />}
      <ul className="cards">
        {data?.charges.map(c => (
          <li key={c.id} className="row-card">
            <div className="grow">
              <strong>{c.clinicName}</strong> <span className={`pill tone-${statusTone(c)}`}>{adminStatusLabel(c)}</span>
              <span className="meta">{c.description} · {brl(c.amount)} · vence {dateBr(c.dueOn)}</span>
              {c.status === 'INFORMED' && c.informedAt && (
                <span className="meta">Informado por {c.informedBy} em {new Date(c.informedAt).toLocaleString('pt-BR')}</span>)}
              {c.status === 'PAID' && <span className="meta">Recebido {brl(c.paidAmount ?? c.amount)} em {dateBr(c.paidOn!)} · {c.method}</span>}
              {c.notes && <span className="meta">{c.notes}</span>}
            </div>
            {(c.status === 'OPEN' || c.status === 'INFORMED') && (
              <div className="charge-actions">
                <button className="primary" onClick={() => setConfirming(c)}>{c.status === 'INFORMED' ? 'Confirmar' : 'Registrar pagamento'}</button>
                {c.status === 'INFORMED' && <button onClick={() => setReasonFor({ charge: c, kind: 'reject' })}>Não recebi</button>}
                <button className="link-button danger" onClick={() => setReasonFor({ charge: c, kind: 'cancel' })}>Cancelar</button>
              </div>
            )}
          </li>
        ))}
      </ul>
      {confirming && <ConfirmSheet charge={confirming} onClose={() => setConfirming(null)}
                                   onDone={() => { setConfirming(null); toast('Pagamento confirmado: receita lançada'); load(month); }} />}
      {reasonFor && <ReasonSheet {...reasonFor} onClose={() => setReasonFor(null)}
                                 onDone={() => { setReasonFor(null); toast(reasonFor.kind === 'reject' ? 'Voltou para em aberto' : 'Mensalidade cancelada'); load(month); }} />}
      {pixOpen && <PlatformPixSheet onClose={() => setPixOpen(false)} />}
    </>
  );
}

function ConfirmSheet({ charge, onClose, onDone }: { charge: SubscriptionCharge; onClose: () => void; onDone: () => void }) {
  const [amount, setAmount] = useState(moneyInput(charge.amount));
  const [paidOn, setPaidOn] = useState('');
  const [method, setMethod] = useState('PIX');
  const [notes, setNotes] = useState('');
  const [renew, setRenew] = useState(true);
  const [error, setError] = useState('');
  async function submit(event: FormEvent) {
    event.preventDefault();
    const value = parseMoney(amount);
    if (!value || value <= 0) { setError('Informe o valor recebido.'); return; }
    try {
      await api(`/api/v1/admin/charges/${charge.id}/confirm`, 'POST',
        { paidAmount: value, paidOn: paidOn || null, method, notes: notes || null, renewAccess: renew });
      onDone();
    } catch (e) { setError((e as Error).message); }
  }
  return (
    <Sheet title={`Confirmar: ${charge.clinicName}`} onClose={onClose}>
      <form className="form" onSubmit={submit} onInput={() => setError('')}>
        <p className="muted small">{charge.description}. Confira no extrato do banco antes de confirmar: vira receita para você e
          despesa para a clínica.</p>
        <div className="row">
          <label>Valor recebido<input inputMode="decimal" required value={amount} onChange={e => setAmount(e.target.value)} /></label>
          <label>Data (vazio = hoje)<input type="date" value={paidOn} onChange={e => setPaidOn(e.target.value)} /></label>
        </div>
        <div className="chips" role="radiogroup" aria-label="Forma de pagamento">
          {PAY_METHODS.map(([id, label]) => (
            <button key={id} type="button" role="radio" aria-checked={method === id} className={method === id ? 'chip on' : 'chip'}
                    onClick={() => setMethod(id)}>{label}</button>))}
        </div>
        <label>Observação (opcional)<input maxLength={300} value={notes} onChange={e => setNotes(e.target.value)} /></label>
        <label className="switch small"><input type="checkbox" checked={renew} onChange={e => setRenew(e.target.checked)} />
          <span>Renovar o acesso da clínica até o próximo vencimento</span></label>
        {error && <p className="error-box" role="alert">{error}</p>}
        <div className="sheet-actions"><button type="button" onClick={onClose}>Voltar</button>
          <button className="primary">Confirmar recebimento</button></div>
      </form>
    </Sheet>
  );
}

function ReasonSheet({ charge, kind, onClose, onDone }: { charge: SubscriptionCharge; kind: 'reject' | 'cancel'; onClose: () => void; onDone: () => void }) {
  const [reason, setReason] = useState('');
  const [error, setError] = useState('');
  async function submit(event: FormEvent) {
    event.preventDefault();
    try { await api(`/api/v1/admin/charges/${charge.id}/${kind}`, 'POST', { reason }); onDone(); } catch (e) { setError((e as Error).message); }
  }
  return (
    <Sheet title={kind === 'reject' ? 'Pagamento não recebido' : 'Cancelar mensalidade'} onClose={onClose}>
      <form className="form" onSubmit={submit} onInput={() => setError('')}>
        <p className="muted small">{charge.clinicName} · {charge.description} · {brl(charge.amount)}.{' '}
          {kind === 'reject' ? 'A mensalidade volta para "a pagar" e a clínica vê o motivo.' : 'A mensalidade sai da cobrança e fica no histórico.'}</p>
        <label>Motivo<input required maxLength={300} value={reason} placeholder={kind === 'reject' ? 'Ex.: não caiu na conta' : 'Ex.: cortesia de lançamento'}
                            onChange={e => setReason(e.target.value)} /></label>
        {error && <p className="error-box" role="alert">{error}</p>}
        <div className="sheet-actions"><button type="button" onClick={onClose}>Voltar</button>
          <button className="primary danger-fill">{kind === 'reject' ? 'Voltar para a pagar' : 'Cancelar mensalidade'}</button></div>
      </form>
    </Sheet>
  );
}

type PixForm = { keyType: PixKeyType | null; key: string | null; name: string | null; city: string | null };

/** Chave Pix da PhysioEvo (aparece no QR Code das mensalidades). */
function PlatformPixSheet({ onClose }: { onClose: () => void }) {
  const { toast } = useUi();
  const [form, setForm] = useState<PixForm | null>(null);
  const [error, setError] = useState('');
  useEffect(() => { api<PixForm>('/api/v1/admin/settings/pix').then(setForm).catch(e => setError((e as Error).message)); }, []);
  if (!form) return null;
  const type = TYPES.find(t => t.id === (form.keyType ?? 'EMAIL'))!;
  async function save(event: FormEvent) {
    event.preventDefault();
    try {
      setForm(await api<PixForm>('/api/v1/admin/settings/pix', 'PUT', { ...form, keyType: form!.keyType ?? type.id }));
      toast('Pix da PhysioEvo salvo');
      onClose();
    } catch (e) { setError((e as Error).message); }
  }
  return (
    <Sheet title="Pix para receber as mensalidades" onClose={onClose}>
      <form className="form" onSubmit={save} onInput={() => setError('')}>
        <p className="muted small">As clínicas pagam pelo QR Code com o valor certo, direto na sua conta, sem taxa. Você confere
          no banco e confirma aqui.</p>
        <div className="chips" role="radiogroup" aria-label="Tipo de chave">
          {TYPES.map(t => (
            <button key={t.id} type="button" role="radio" aria-checked={type.id === t.id} className={type.id === t.id ? 'chip on' : 'chip'}
                    onClick={() => setForm({ ...form, keyType: t.id, key: form.keyType === t.id ? form.key : '' })}>{t.label}</button>))}
        </div>
        <label>Chave Pix<input required inputMode={type.inputMode} placeholder={type.placeholder} value={form.key ?? ''}
                               onChange={e => setForm({ ...form, keyType: type.id, key: type.id === 'TELEFONE' ? maskPhone(e.target.value)
                                 : type.id === 'CPF' ? maskCpf(e.target.value) : e.target.value })} /></label>
        <div className="row">
          <label>Nome de quem recebe<input required maxLength={60} value={form.name ?? ''} onChange={e => setForm({ ...form, name: e.target.value })} /></label>
          <label>Cidade<input required maxLength={40} value={form.city ?? ''} onChange={e => setForm({ ...form, city: e.target.value })} /></label>
        </div>
        {error && <p className="error-box" role="alert">{error}</p>}
        <div className="sheet-actions"><button type="button" onClick={onClose}>Cancelar</button><button className="primary">Salvar Pix</button></div>
      </form>
    </Sheet>
  );
}

/** Ficha da clínica → plano (valor por clínica, para promoções) e mensalidades dela. */
export function ClinicBilling({ clinicId }: { clinicId: string }) {
  const { toast } = useUi();
  const [price, setPrice] = useState('');
  const [day, setDay] = useState('');
  const [charges, setCharges] = useState<SubscriptionCharge[]>([]);
  const [error, setError] = useState('');
  const load = useCallback(() => {
    api<SubscriptionPlan>(`/api/v1/admin/clinics/${clinicId}/plan`).then(p => { setPrice(moneyInput(p.planPrice)); setDay(p.billingDay ? String(p.billingDay) : ''); })
      .catch(e => setError((e as Error).message));
    api<SubscriptionCharge[]>(`/api/v1/admin/clinics/${clinicId}/charges`).then(setCharges).catch(() => undefined);
  }, [clinicId]);
  useEffect(() => { load(); }, [load]);
  async function save(event: FormEvent) {
    event.preventDefault();
    const value = price.trim() ? parseMoney(price) : null;
    if (price.trim() && (value === null || value < 0)) { setError('Valor inválido. Ex.: 79,90'); return; }
    try {
      await api(`/api/v1/admin/clinics/${clinicId}/plan`, 'PUT', { planPrice: value, billingDay: day ? Number(day) : null });
      toast('Plano salvo');
    } catch (e) { setError((e as Error).message); }
  }
  return (
    <section className="stack">
      <form className="form card" onSubmit={save} onInput={() => setError('')}>
        <h3>Plano e mensalidade</h3>
        <div className="row">
          <label>Valor por mês<input inputMode="decimal" placeholder="Ex.: 79,90 (vazio = sem mensalidade)" value={price}
                                     onChange={e => setPrice(e.target.value)} /></label>
          <label>Dia do vencimento<input type="number" min={1} max={28} placeholder="10" value={day} onChange={e => setDay(e.target.value)} /></label>
        </div>
        <p className="muted small">Cada clínica pode ter o seu valor (promoções). A mensalidade é gerada no Financeiro.</p>
        {error && <p className="error-box" role="alert">{error}</p>}
        <button className="primary">Salvar plano</button>
      </form>
      {charges.length > 0 && (
        <div>
          <h3>Mensalidades</h3>
          <ul className="admin-history">
            {charges.map(c => (
              <li key={c.id}><strong>{c.description}</strong> <span className={`pill tone-${statusTone(c)}`}>{adminStatusLabel(c)}</span>
                <span className="muted small"> · {brl(c.amount)} · vence {dateBr(c.dueOn)}{c.paidOn ? ` · pago em ${dateBr(c.paidOn)}` : ''}</span></li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}
