import { useCallback, useEffect, useState } from 'react';
import { api, type ClinicNotice, type NoticeLevel } from './api';
import { Icon, Modal, type IconName } from './ui';

export const LEVEL_LABEL: Record<NoticeLevel, string> = { INFO: 'Informativo', IMPORTANT: 'Importante', URGENT: 'Urgente' };
const LEVEL_ICON: Record<NoticeLevel, IconName> = { INFO: 'sparkle', IMPORTANT: 'bell', URGENT: 'alert' };

/** Faixa de aviso (no topo do painel e na prévia da administração). */
export function NoticeBanner({ title, body, level, onDismiss }: { title: string; body: string; level: NoticeLevel; onDismiss?: () => void }) {
  return (
    <div className={`platform-banner lv-${level}`} role="status">
      <Icon name={LEVEL_ICON[level]} size={20} />
      <div className="grow"><strong>{title || 'Título do aviso'}</strong><p>{body || 'Mensagem do aviso.'}</p></div>
      {onDismiss && <button className="link-button" onClick={onDismiss}>Dispensar</button>}
    </div>
  );
}

/** Conteúdo da janela de aviso (também usado na prévia). */
export function NoticeCard({ title, body, level }: { title: string; body: string; level: NoticeLevel }) {
  return (
    <div className={`platform-card lv-${level}`}>
      <span className="platform-card-tag"><Icon name={LEVEL_ICON[level]} size={16} /> {LEVEL_LABEL[level]} · PhysioEvo</span>
      <h3>{title || 'Título do aviso'}</h3>
      <p>{body || 'Mensagem do aviso.'}</p>
    </div>
  );
}

/**
 * Avisos da equipe PhysioEvo para a clínica: faixas no topo (até dispensar) e janelas, uma de cada vez, só quando
 * {@code active} (depois do aviso de vencimento). {@code onReady}: acabaram as janelas.
 */
export function PlatformNotices({ active, onReady }: { active: boolean; onReady: () => void }) {
  const [items, setItems] = useState<ClinicNotice[] | null>(null);
  const load = useCallback(() => api<ClinicNotice[]>('/api/v1/notices').then(setItems).catch(() => setItems([])), []);
  useEffect(() => {
    load();
    const timer = setInterval(load, 5 * 60_000);
    return () => clearInterval(timer);
  }, [load]);

  const unread = items?.filter(n => !n.read) ?? [];
  const modal = unread.find(n => n.display === 'MODAL');
  useEffect(() => { if (items && active && !modal) onReady(); }, [items, active, modal, onReady]);

  async function read(id: string) {
    setItems(list => list?.map(n => (n.id === id ? { ...n, read: true } : n)) ?? null);
    await api(`/api/v1/notices/${id}/read`, 'POST').catch(() => undefined);
  }

  return (
    <>
      {unread.filter(n => n.display === 'BANNER').map(n => (
        <NoticeBanner key={n.id} title={n.title} body={n.body} level={n.level} onDismiss={() => read(n.id)} />
      ))}
      {active && modal && (
        <Modal key={modal.id} title="Aviso da PhysioEvo" onClose={() => read(modal.id)} className="platform-modal">
          <NoticeCard title={modal.title} body={modal.body} level={modal.level} />
          <div className="actions end"><button type="button" className="primary" onClick={() => read(modal.id)}>Entendi</button></div>
        </Modal>
      )}
    </>
  );
}
