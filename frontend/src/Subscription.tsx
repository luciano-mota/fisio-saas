import { useCallback, useEffect, useState } from 'react';
import { api, type MySubscription, type PlatformPixCharge, type SubscriptionCharge } from './api';
import { today } from './dates';
import { brl, dateBr } from './finance';
import { chargeNotice, chargeSeenKey, clinicStatusLabel, statusTone } from './subscription';
import { EmptyState, Icon, Modal, Sheet, useUi } from './ui';

/** QR Code Pix da PhysioEvo com o valor da mensalidade, "copia e cola" e o botão "Pagamento realizado". */
function PixPay({ charge, owner, onInformed }: { charge: SubscriptionCharge; owner: boolean; onInformed: (c: SubscriptionCharge) => void }) {
  const { toast } = useUi();
  const [pix, setPix] = useState<PlatformPixCharge | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    api<PlatformPixCharge>(`/api/v1/subscription/charges/${charge.id}/pix`).then(setPix).catch(e => setError((e as Error).message));
  }, [charge.id]);

  async function copy() {
    if (!pix) return;
    try { await navigator.clipboard.writeText(pix.code); toast('Código Pix copiado'); } catch { setError('Não deu para copiar; selecione o código e copie.'); }
  }
  async function informed() {
    setBusy(true);
    try {
      const result = await api<SubscriptionCharge>(`/api/v1/subscription/charges/${charge.id}/informed`, 'POST');
      toast('Pagamento informado. Assim que a PhysioEvo confirmar, ele aparece como pago.');
      onInformed(result);
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }

  return (
    <div className="sub-pay">
      <div className="sub-pay-head"><span>{charge.description}</span><strong>{brl(charge.amount)}</strong>
        <small>Vencimento {dateBr(charge.dueOn)}</small></div>
      {error && <p className="error-box" role="alert">{error}</p>}
      {pix && (
        <>
          {/* SVG gerado pelo próprio servidor (QR Code do BR Code). */}
          <div className="sub-qr" aria-label="QR Code Pix" role="img" dangerouslySetInnerHTML={{ __html: pix.qrSvg }} />
          <p className="muted small">Abra o app do banco, escolha Pix → Ler QR Code. Recebedor: <b>{pix.receiver}</b>.</p>
          <div className="sub-code"><code>{pix.code}</code>
            <button type="button" onClick={copy}><Icon name="check" size={16} /> Copiar código</button></div>
        </>
      )}
      {owner
        ? <button className="primary big" disabled={busy} onClick={informed}>{busy ? 'Enviando…' : 'Pagamento realizado'}</button>
        : <p className="notice small">Só a responsável da clínica pode informar o pagamento.</p>}
    </div>
  );
}

export function PayChargeSheet({ charge, owner, onClose, onInformed }: {
  charge: SubscriptionCharge; owner: boolean; onClose: () => void; onInformed: (c: SubscriptionCharge) => void;
}) {
  return (
    <Sheet title="Pagar mensalidade com Pix" onClose={onClose}>
      <PixPay charge={charge} owner={owner} onInformed={c => { onInformed(c); onClose(); }} />
    </Sheet>
  );
}

function seen(key: string) { try { return localStorage.getItem(key) === '1'; } catch { return false; } }
function markSeen(key: string) { try { localStorage.setItem(key, '1'); } catch { /* sem armazenamento */ } }

/**
 * Aviso de mensalidade no painel (faixa e, nos dias certos, janela já com o QR Code). Fica na fila: depois do aviso
 * de vencimento do acesso e antes dos avisos da plataforma.
 */
export function SubscriptionNotice({ email, owner, active, onReady }: { email: string; owner: boolean; active: boolean; onReady: () => void }) {
  const [data, setData] = useState<MySubscription | null>(null);
  const [open, setOpen] = useState(false);
  const [paying, setPaying] = useState(false);
  const load = useCallback(() => api<MySubscription>('/api/v1/subscription').then(setData).catch(() => setData({ plan: { planPrice: null, billingDay: null }, pixAvailable: false, charges: [] })), []);
  useEffect(() => { load(); }, [load]);
  const notice = data ? chargeNotice(data.charges, today()) : null;
  const key = notice ? chargeSeenKey(email, notice.charge.id, today()) : '';
  useEffect(() => {
    if (!data || !active) return;
    if (notice?.modal && notice.charge.status === 'OPEN' && !seen(key)) setOpen(true);
    else onReady();
  }, [data, active]); // eslint-disable-line react-hooks/exhaustive-deps
  if (!data || !notice) return null;
  const close = () => { markSeen(key); setOpen(false); onReady(); };
  const canPay = notice.charge.status === 'OPEN' && data.pixAvailable;

  return (
    <>
      <div className={`access-banner ${notice.level}`} role="status">
        <Icon name={notice.charge.status === 'INFORMED' ? 'clock' : 'money'} size={20} />
        <div className="grow"><strong>{notice.title}.</strong> <span>{notice.text}</span>{' '}
          <b>{brl(notice.charge.amount)}</b></div>
        {canPay && <button className="primary" onClick={() => setPaying(true)}>Pagar com Pix</button>}
      </div>
      {open && (
        <Modal key={notice.charge.id} title={notice.title} onClose={close} className="sub-modal">
          <p>{notice.text}</p>
          {canPay ? <PixPay charge={notice.charge} owner={owner} onInformed={() => { close(); load(); }} />
            : <p className="notice small">Fale com o suporte para receber os dados de pagamento.</p>}
          <div className="actions end"><button type="button" onClick={close}>Pagar depois</button></div>
        </Modal>
      )}
      {paying && <PayChargeSheet charge={notice.charge} owner={owner} onClose={() => setPaying(false)} onInformed={() => load()} />}
    </>
  );
}

/** Financeiro → Assinatura: as mensalidades da PhysioEvo (despesas da clínica). */
export function SubscriptionSection({ owner }: { owner: boolean }) {
  const [data, setData] = useState<MySubscription | null>(null);
  const [paying, setPaying] = useState<SubscriptionCharge | null>(null);
  const [error, setError] = useState('');
  const load = useCallback(() => api<MySubscription>('/api/v1/subscription').then(setData).catch(e => setError((e as Error).message)), []);
  useEffect(() => { load(); }, [load]);
  if (!data) return error ? <p className="error-box">{error}</p> : null;
  return (
    <div className="stack">
      <div className="card sub-plan">
        <div><span className="muted small">Seu plano PhysioEvo</span>
          <strong>{data.plan.planPrice != null ? `${brl(data.plan.planPrice)} por mês` : 'Sem mensalidade definida'}</strong>
          {data.plan.billingDay && <span className="muted small">Vence todo dia {data.plan.billingDay}</span>}</div>
        <p className="muted small">As mensalidades pagas entram como despesa no resumo do mês. Depois de pagar pelo Pix, toque em
          "Pagamento realizado": fica aguardando a confirmação da PhysioEvo.</p>
      </div>
      {data.charges.length === 0 && <EmptyState icon="money" title="Nenhuma mensalidade ainda" />}
      <ul className="cards">
        {data.charges.map(c => (
          <li key={c.id} className="row-card">
            <div className="grow">
              <strong>{c.description}</strong> <span className={`pill tone-${statusTone(c)}`}>{clinicStatusLabel(c)}</span>
              <span className="meta">{brl(c.status === 'PAID' && c.paidAmount != null ? c.paidAmount : c.amount)} ·{' '}
                {c.status === 'PAID' && c.paidOn ? `paga em ${dateBr(c.paidOn)}` : `vence ${dateBr(c.dueOn)}`}</span>
              {c.notes && c.status === 'OPEN' && <span className="meta warn-text">{c.notes}</span>}
            </div>
            {c.status === 'OPEN' && data.pixAvailable && <button className="primary" onClick={() => setPaying(c)}>Pagar com Pix</button>}
          </li>
        ))}
      </ul>
      {paying && <PayChargeSheet charge={paying} owner={owner} onClose={() => setPaying(null)} onInformed={() => load()} />}
    </div>
  );
}
