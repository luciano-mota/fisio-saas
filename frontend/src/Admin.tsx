import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api, signOut, type AdminClinic, type AdminClinicDetail, type AdminMe, type AdminOverview, type EffectiveStatus } from './api';
import { dateBrFull, inDays, STATUS_LABEL } from './access';
import { AdminFinance, ClinicBilling } from './AdminFinance';
import { AdminNotices } from './AdminNotices';
import { formatPhone } from './format';
import { navigate, usePath } from './router';
import { EmptyState, Icon, Sheet, UiProvider, useUi } from './ui';

type Action = 'TRIAL' | 'FREE' | 'ACTIVE' | 'SUSPEND' | 'BLOCK';
const ACTIONS: { id: Action; label: string; help: string; period: 'required' | 'optional' | 'none' }[] = [
  { id: 'TRIAL', label: 'Teste grátis', period: 'required', help: 'Período de teste com data para acabar. Ao vencer, o painel fica só para consulta.' },
  { id: 'FREE', label: 'Acesso gratuito', period: 'optional', help: 'Libera tudo sem cobrança, pelo tempo que você escolher (ou sem prazo).' },
  { id: 'ACTIVE', label: 'Assinatura ativa', period: 'optional', help: 'Assinante em dia. Informe até quando está pago, se quiser o aviso de vencimento.' },
  { id: 'SUSPEND', label: 'Suspender', period: 'none', help: 'Só consulta: não altera nada e o site fica no ar sem agendamento. Os dados ficam guardados.' },
  { id: 'BLOCK', label: 'Bloquear', period: 'none', help: 'Ninguém da clínica entra e o site sai do ar. Os logins abertos caem. Os dados ficam guardados.' }
];
const QUICK_DAYS = [7, 15, 30, 60, 90, 180, 365];
const TILES: EffectiveStatus[] = ['TRIAL', 'ACTIVE', 'FREE', 'EXPIRED', 'SUSPENDED', 'BLOCKED'];

function deadline(c: AdminClinic): string {
  if (c.effectiveStatus === 'SUSPENDED' || c.effectiveStatus === 'BLOCKED') return c.statusReason ?? '';
  if (!c.accessUntil || c.daysLeft == null) return c.effectiveStatus === 'TRIAL' ? 'Teste sem prazo definido' : 'Sem prazo';
  if (c.daysLeft < 0) return `Venceu em ${dateBrFull(c.accessUntil)}`;
  return `Até ${dateBrFull(c.accessUntil)} (${inDays(c.daysLeft)})`;
}

/** Administração da plataforma: assinantes, teste grátis, acesso gratuito, suspensão e bloqueio. Sem dados de pacientes. */
export function AdminPanel({ me, onLogout }: { me: AdminMe; onLogout: () => void }) {
  return <UiProvider><AdminHome me={me} onLogout={onLogout} /></UiProvider>;
}

const SECTIONS = [{ path: '/admin', label: 'Clínicas', icon: 'building' }, { path: '/admin/financeiro', label: 'Financeiro', icon: 'money' },
  { path: '/admin/avisos', label: 'Avisos', icon: 'bell' }] as const;

function AdminHome({ me, onLogout }: { me: AdminMe; onLogout: () => void }) {
  const path = usePath();
  const section = SECTIONS.find(s => s.path !== '/admin' && path.startsWith(s.path))?.path ?? '/admin';
  return (
    <div className="admin">
      <header className="admin-top">
        <img src="/brand/physioevo-logo.svg" alt="PhysioEvo" height={30} />
        <span className="admin-tag">Administração</span>
        <nav className="admin-nav" aria-label="Seções da administração">
          {SECTIONS.map(s => (
            <a key={s.path} href={s.path} className={section === s.path ? 'active' : ''} aria-current={section === s.path ? 'page' : undefined}
               onClick={e => { e.preventDefault(); navigate(s.path); }}><Icon name={s.icon} size={18} />{s.label}</a>
          ))}
        </nav>
        <span className="grow" />
        <span className="muted small admin-email">{me.email}</span>
        <button className="link-button" onClick={async () => { await signOut().catch(() => undefined); onLogout(); }}>
          <Icon name="logout" size={18} /> Sair</button>
      </header>
      <main className="admin-main">
        {section === '/admin/avisos' ? <AdminNotices /> : section === '/admin/financeiro' ? <AdminFinance /> : <AdminClinics />}
      </main>
    </div>
  );
}

function AdminClinics() {
  const [data, setData] = useState<AdminOverview | null>(null);
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<EffectiveStatus | 'SOON' | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [error, setError] = useState('');

  const load = useCallback(() => api<AdminOverview>('/api/v1/admin/clinics?' + new URLSearchParams({ q: query }))
    .then(setData).catch(e => setError((e as Error).message)), [query]);
  useEffect(() => {
    const timer = setTimeout(load, 250);
    return () => clearTimeout(timer);
  }, [load]);

  const soon = (c: AdminClinic) => c.daysLeft != null && c.daysLeft >= 0 && c.daysLeft <= 7
    && c.effectiveStatus !== 'SUSPENDED' && c.effectiveStatus !== 'BLOCKED';
  const shown = data?.clinics.filter(c => !filter || (filter === 'SOON' ? soon(c) : c.effectiveStatus === filter)) ?? [];

  return (
    <>
        <h1>Clínicas assinantes</h1>
        <p className="muted">Situação de cada conta. Aqui não aparecem dados de pacientes; só a quantidade, para acompanhar o uso.</p>
        {error && <p className="error-box" role="alert">{error}</p>}
        {data && (
          <div className="admin-tiles">
            {TILES.map(s => (
              <button key={s} className={`admin-tile st-${s}${filter === s ? ' on' : ''}`} aria-pressed={filter === s}
                      onClick={() => setFilter(f => (f === s ? null : s))}>
                <strong>{data.counts[s] ?? 0}</strong><span>{STATUS_LABEL[s]}</span></button>
            ))}
            <button className={`admin-tile st-SOON${filter === 'SOON' ? ' on' : ''}`} aria-pressed={filter === 'SOON'}
                    onClick={() => setFilter(f => (f === 'SOON' ? null : 'SOON'))}>
              <strong>{data.expiringSoon}</strong><span>Vencem em 7 dias</span></button>
          </div>
        )}
        <div className="search">
          <Icon name="search" size={18} />
          <input type="search" placeholder="Buscar por nome, endereço ou e-mail" aria-label="Buscar clínica"
                 value={query} onChange={e => setQuery(e.target.value)} />
        </div>
        {data && shown.length === 0 && <EmptyState icon="building" title="Nenhuma clínica encontrada" />}
        <ul className="cards">
          {shown.map(c => (
            <li key={c.id} className="row-card clickable" onClick={() => setSelected(c.id)}>
              <div className="grow">
                <strong>{c.displayName}</strong> <span className={`pill st-${c.effectiveStatus}`}>{STATUS_LABEL[c.effectiveStatus]}</span>
                <span className="meta">{[c.ownerEmail, `/c/${c.slug}`].filter(Boolean).join(' · ')}</span>
                <span className="meta">{deadline(c)}{' · '}{c.users} {c.users === 1 ? 'usuário' : 'usuários'} · {c.patients} pacientes</span>
              </div>
              <button className="icon-button" aria-label={`Gerenciar ${c.displayName}`} onClick={e => { e.stopPropagation(); setSelected(c.id); }}>
                <Icon name="right" /></button>
            </li>
          ))}
        </ul>
      {selected && <ClinicSheet id={selected} onClose={() => setSelected(null)} onChanged={load} />}
    </>
  );
}

function ClinicSheet({ id, onClose, onChanged }: { id: string; onClose: () => void; onChanged: () => void }) {
  const { toast, confirm } = useUi();
  const [detail, setDetail] = useState<AdminClinicDetail | null>(null);
  const [action, setAction] = useState<Action>('FREE');
  const [mode, setMode] = useState<'days' | 'date' | 'none'>('days');
  const [days, setDays] = useState(30);
  const [until, setUntil] = useState('');
  const [reason, setReason] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    api<AdminClinicDetail>(`/api/v1/admin/clinics/${id}`).then(setDetail).catch(e => setError((e as Error).message));
  }, [id]);
  const spec = ACTIONS.find(a => a.id === action)!;
  const effectiveMode = spec.period === 'none' ? 'none' : spec.period === 'required' && mode === 'none' ? 'days' : mode;

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError('');
    if (!reason.trim()) { setError('Informe o motivo.'); return; }
    if ((action === 'SUSPEND' || action === 'BLOCK') && !await confirm(
      `${spec.label} ${detail?.clinic.displayName}? ${spec.help}`, { confirmLabel: spec.label, danger: true })) return;
    setBusy(true);
    try {
      const result = await api<AdminClinicDetail>(`/api/v1/admin/clinics/${id}/access`, 'POST', {
        action, reason, days: effectiveMode === 'days' ? days : null, until: effectiveMode === 'date' ? until || null : null
      });
      setDetail(result);
      setReason('');
      toast('Situação atualizada');
      onChanged();
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }

  const c = detail?.clinic;
  return (
    <Sheet title={c?.displayName ?? 'Clínica'} onClose={onClose}>
      {!c ? (error ? <p className="error-box">{error}</p> : <p className="muted">Carregando…</p>) : (
        <div className="stack">
          <div className="admin-facts">
            <div><span>Situação</span><strong><span className={`pill st-${c.effectiveStatus}`}>{STATUS_LABEL[c.effectiveStatus]}</span></strong></div>
            <div><span>Prazo</span><strong>{c.accessUntil ? `${dateBrFull(c.accessUntil)}${c.daysLeft != null && c.daysLeft >= 0 ? ` (${inDays(c.daysLeft)})` : ''}` : 'Sem prazo'}</strong></div>
            <div><span>Responsável</span><strong>{c.ownerName ?? '—'}</strong></div>
            <div><span>E-mail</span><strong>{c.ownerEmail ?? '—'}</strong></div>
            <div><span>WhatsApp</span><strong>{c.whatsapp ? formatPhone(c.whatsapp) : '—'}</strong></div>
            <div><span>Cliente desde</span><strong>{dateBrFull(c.createdAt.slice(0, 10))}</strong></div>
            <div><span>Site</span><strong><a href={`/c/${c.slug}`} target="_blank" rel="noreferrer">/c/{c.slug}</a>{!c.publicPageEnabled && ' (desligado)'}</strong></div>
            <div><span>Uso</span><strong>{c.users} usuários · {c.patients} pacientes</strong></div>
          </div>
          {c.statusReason && <p className="muted small">Último motivo: {c.statusReason}</p>}

          <form className="form card admin-action" onSubmit={submit} onInput={() => setError('')}>
            <h3>Mudar a situação</h3>
            <div className="chips" role="radiogroup" aria-label="Ação">
              {ACTIONS.map(a => (
                <button key={a.id} type="button" role="radio" aria-checked={action === a.id}
                        className={`chip${action === a.id ? ' on' : ''}${a.id === 'SUSPEND' || a.id === 'BLOCK' ? ' danger' : ''}`}
                        onClick={() => setAction(a.id)}>{a.label}</button>
              ))}
            </div>
            <p className="muted small">{spec.help}</p>
            {spec.period !== 'none' && (
              <fieldset className="field">
                <legend>Por quanto tempo</legend>
                <div className="chips">
                  {QUICK_DAYS.map(d => (
                    <button key={d} type="button" className={effectiveMode === 'days' && days === d ? 'chip on' : 'chip'}
                            onClick={() => { setMode('days'); setDays(d); }}>{d === 365 ? '1 ano' : `${d} dias`}</button>
                  ))}
                  <button type="button" className={effectiveMode === 'date' ? 'chip on' : 'chip'} onClick={() => setMode('date')}>Até a data…</button>
                  {spec.period === 'optional' && (
                    <button type="button" className={effectiveMode === 'none' ? 'chip on' : 'chip'} onClick={() => setMode('none')}>Sem prazo</button>
                  )}
                </div>
                {effectiveMode === 'days' && (
                  <label className="inline">Dias<input type="number" min={1} max={3650} value={days}
                                                       onChange={e => setDays(Number(e.target.value))} /></label>
                )}
                {effectiveMode === 'date' && (
                  <label>Até<input type="date" required value={until} onChange={e => setUntil(e.target.value)} /></label>
                )}
              </fieldset>
            )}
            <label>Motivo (fica no histórico)
              <textarea required maxLength={300} rows={2} placeholder="Ex.: pagamento confirmado; parceria; atraso de 15 dias"
                        value={reason} onChange={e => setReason(e.target.value)} /></label>
            {error && <p className="error-box" role="alert">{error}</p>}
            <button className={action === 'SUSPEND' || action === 'BLOCK' ? 'primary danger-fill' : 'primary'} disabled={busy}>
              {busy ? 'Salvando…' : `Confirmar: ${spec.label.toLowerCase()}`}</button>
          </form>

          <ClinicBilling clinicId={c.id} />

          <section>
            <h3>Histórico</h3>
            {detail.history.length === 0 && <p className="muted small">Nenhuma ação da plataforma ainda.</p>}
            <ul className="admin-history">
              {detail.history.map(h => (
                <li key={h.createdAt + h.action}>
                  <strong>{ACTIONS.find(a => a.id === h.action)?.label ?? h.action}</strong>
                  {h.accessUntil && <> até {dateBrFull(h.accessUntil)}</>}
                  <span className="muted small"> · {new Date(h.createdAt).toLocaleString('pt-BR')} · {h.adminEmail}</span>
                  <p>{h.reason}</p>
                </li>
              ))}
            </ul>
          </section>
        </div>
      )}
    </Sheet>
  );
}
