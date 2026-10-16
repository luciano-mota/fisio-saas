import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from './api';
import { alertTitle, latestSeen, newSince, requestLine, tabTitle, type RecentRequest } from './requestAlerts';
import { navigate } from './router';
import { Icon } from './ui';

const POLL_MS = 20_000;
const SOUND_KEY = 'physioevo:som-pedidos';

export function soundEnabled(): boolean {
  try { return localStorage.getItem(SOUND_KEY) !== '0'; } catch { return true; }
}
export function setSoundEnabled(on: boolean) {
  try { localStorage.setItem(SOUND_KEY, on ? '1' : '0'); } catch { /* sem armazenamento: vale só nesta visita */ }
}

/** Dois toques curtos (sem arquivo de áudio). O navegador só toca depois de a pessoa ter clicado na página. */
export function chime() {
  if (!soundEnabled()) return;
  try {
    const Ctx = window.AudioContext ?? (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
    const ctx = new Ctx();
    [[880, 0], [1320, 0.16]].forEach(([freq, at]) => {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.frequency.value = freq;
      gain.gain.setValueAtTime(0.0001, ctx.currentTime + at);
      gain.gain.exponentialRampToValueAtTime(0.18, ctx.currentTime + at + 0.02);
      gain.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + at + 0.25);
      osc.connect(gain).connect(ctx.destination);
      osc.start(ctx.currentTime + at);
      osc.stop(ctx.currentTime + at + 0.3);
    });
    setTimeout(() => ctx.close().catch(() => undefined), 800);
  } catch { /* sem áudio: segue só com o aviso na tela */ }
}

/** Notificação do sistema (Windows, Android…) quando o painel está em segundo plano e a pessoa permitiu. */
function systemNotify(fresh: RecentRequest[]) {
  if (!('Notification' in window) || Notification.permission !== 'granted' || !document.hidden) return;
  try {
    const n = new Notification(alertTitle(fresh.length), {
      body: fresh.map(requestLine).join('\n'), icon: '/brand/icon-192.png', tag: 'physioevo-pedidos'
    });
    n.onclick = () => { window.focus(); navigate('/painel/solicitacoes'); n.close(); };
  } catch { /* alguns celulares só aceitam notificação pelo service worker */ }
}

/**
 * Acompanha os pedidos de agendamento do site sem recarregar a página: consulta a cada 20 s e ao voltar para a aba.
 * Pedido novo: aviso na tela, som, notificação do sistema e o número no título da aba. {@code version} muda quando a
 * lista de pendentes muda, para as telas recarregarem.
 */
export function useRequestWatch(clinicName: string) {
  const [pending, setPending] = useState(0);
  const [version, setVersion] = useState(0);
  const [alerts, setAlerts] = useState<RecentRequest[]>([]);
  const seen = useRef<number | null>(null);
  const signature = useRef<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const c = await api<{ pending: number; recent: RecentRequest[] }>('/api/v1/booking-requests/count');
      setPending(c.pending);
      const fresh = newSince(c.recent, seen.current);
      seen.current = latestSeen(c.recent, seen.current);
      const sig = `${c.pending}:${c.recent.map(r => r.id).join(',')}`;
      if (signature.current !== null && sig !== signature.current) setVersion(v => v + 1);
      signature.current = sig;
      if (fresh.length > 0) {
        setAlerts(list => [...list, ...fresh].slice(-3));
        chime();
        systemNotify(fresh);
      }
    } catch { /* sem conexão agora: tenta de novo no próximo ciclo */ }
  }, []);

  useEffect(() => {
    refresh();
    const timer = setInterval(refresh, POLL_MS);
    const wake = () => { if (!document.hidden) refresh(); };
    document.addEventListener('visibilitychange', wake);
    window.addEventListener('focus', wake);
    return () => {
      clearInterval(timer);
      document.removeEventListener('visibilitychange', wake);
      window.removeEventListener('focus', wake);
    };
  }, [refresh]);

  useEffect(() => { document.title = tabTitle(clinicName, pending); }, [clinicName, pending]);
  useEffect(() => () => { document.title = 'PhysioEvo'; }, []);

  return { pending, version, refresh, alerts, dismiss: () => setAlerts([]) };
}

/** Cartão de "novo pedido" no canto da tela, com atalho para responder. */
export function RequestAlert({ alerts, onDismiss }: { alerts: RecentRequest[]; onDismiss: () => void }) {
  if (alerts.length === 0) return null;
  return (
    <div className="request-alert" role="status" aria-live="polite">
      <span className="request-alert-icon"><Icon name="bell" size={20} /></span>
      <div className="grow">
        <strong>{alertTitle(alerts.length)}</strong>
        {alerts.map(a => <span key={a.id} className="small">{requestLine(a)}</span>)}
        <div className="request-alert-actions">
          <button className="primary" onClick={() => { onDismiss(); navigate('/painel/solicitacoes'); }}>Ver pedido{alerts.length > 1 ? 's' : ''}</button>
          <button className="link-button" onClick={onDismiss}>Depois</button>
        </div>
      </div>
      <button className="icon-button" aria-label="Fechar aviso" onClick={onDismiss}><Icon name="x" size={18} /></button>
    </div>
  );
}

/** Preferências do aviso: som e notificação do navegador (fica na aba Solicitações). */
export function AlertSettings() {
  const [sound, setSound] = useState(soundEnabled());
  const supported = typeof window !== 'undefined' && 'Notification' in window;
  const [permission, setPermission] = useState(supported ? Notification.permission : 'denied');
  return (
    <div className="alert-settings">
      <label className="switch small"><input type="checkbox" checked={sound}
                                             onChange={e => { setSound(e.target.checked); setSoundEnabled(e.target.checked); if (e.target.checked) chime(); }} />
        <span>Tocar som quando chegar pedido</span></label>
      {supported && permission === 'default' && (
        <button className="link-button" onClick={async () => setPermission(await Notification.requestPermission())}>
          <Icon name="bell" size={16} /> Avisar também com o painel minimizado</button>
      )}
      {supported && permission === 'granted' && <span className="muted small"><Icon name="check" size={14} /> Notificações do navegador ativadas</span>}
      {supported && permission === 'denied' && (
        <span className="muted small">Notificações bloqueadas no navegador; libere nas configurações do site para receber com o painel minimizado.</span>
      )}
    </div>
  );
}
