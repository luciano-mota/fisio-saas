import { useEffect, useState } from 'react';
import { api, type ClinicAccessView } from './api';
import { accessNotice, accessSeenKey } from './access';
import { today } from './dates';
import { Icon, Modal } from './ui';

function seen(key: string): boolean {
  try { return localStorage.getItem(key) === '1'; } catch { return false; }
}
function markSeen(key: string) {
  try { localStorage.setItem(key, '1'); } catch { /* sem armazenamento: a janela volta no próximo acesso */ }
}

/**
 * Aviso de vencimento dentro do painel: faixa no topo a partir de 7 dias antes, e uma janela nos dias 7, 3, 1 e no
 * próprio dia (uma vez por dia). Vencida ou suspensa: avisa que o painel está só para consulta.
 * {@code onReady} avisa quando a janela fechou (ou nem abriu), para outros avisos não abrirem por cima.
 */
export function AccessNotice({ email, onReady }: { email: string; onReady: () => void }) {
  const [view, setView] = useState<ClinicAccessView | null>(null);
  const [open, setOpen] = useState(false);
  useEffect(() => {
    api<ClinicAccessView>('/api/v1/clinic/access').then(v => {
      setView(v);
      const notice = accessNotice(v);
      if (notice?.modal && !seen(accessSeenKey(email, today(), v.warning))) setOpen(true);
      else onReady();
    }).catch(onReady);
  }, [email]);
  const notice = view && accessNotice(view);
  if (!view || !notice) return null;
  const close = () => { markSeen(accessSeenKey(email, today(), view.warning)); setOpen(false); onReady(); };
  const contact = view.contactEmail
    ? <a href={`mailto:${view.contactEmail}?subject=${encodeURIComponent('Assinatura PhysioEvo')}`}>Falar com o suporte</a>
    : null;

  return (
    <>
      <div className={`access-banner ${notice.level}`} role="status">
        <Icon name={notice.level === 'danger' ? 'block' : 'clock'} size={20} />
        <div><strong>{notice.title}.</strong> <span>{notice.text}</span> {contact}</div>
      </div>
      {open && (
        <Modal title={notice.title} onClose={close} className="access-modal">
          <p>{notice.text}</p>
          <div className="actions end">
            {view.contactEmail && <a className="button" href={`mailto:${view.contactEmail}`}>Falar com o suporte</a>}
            <button type="button" className="primary" onClick={close}>Entendi</button>
          </div>
        </Modal>
      )}
    </>
  );
}
