import { useEffect, useState, type FormEvent } from 'react';
import { api, type AdminClinic, type AdminNotice, type AdminOverview, type NoticeDisplay, type NoticeLevel } from './api';
import { dateBrFull } from './access';
import { LEVEL_LABEL, NoticeBanner, NoticeCard } from './PlatformNotices';
import { EmptyState, Icon, Sheet, useUi } from './ui';

const STATUS_LABEL: Record<AdminNotice['status'], string> = {
  SCHEDULED: 'Agendado', LIVE: 'No ar', ENDED: 'Encerrado', INACTIVE: 'Desativado'
};
const LEVELS: NoticeLevel[] = ['INFO', 'IMPORTANT', 'URGENT'];
const DISPLAYS: { id: NoticeDisplay; label: string; help: string }[] = [
  { id: 'BANNER', label: 'Faixa no topo', help: 'Fica no topo do painel até a pessoa dispensar.' },
  { id: 'MODAL', label: 'Janela', help: 'Abre uma janela ao entrar; some depois de "Entendi".' }
];

type Form = { id?: string; title: string; body: string; level: NoticeLevel; display: NoticeDisplay; clinicId: string;
  startsOn: string; endsOn: string; active: boolean };
const EMPTY: Form = { title: '', body: '', level: 'INFO', display: 'BANNER', clinicId: '', startsOn: '', endsOn: '', active: true };

/** Avisos da equipe PhysioEvo para as clínicas (todas ou uma), com prévia e contagem de leituras. */
export function AdminNotices() {
  const [items, setItems] = useState<AdminNotice[] | null>(null);
  const [clinics, setClinics] = useState<AdminClinic[]>([]);
  const [form, setForm] = useState<Form | null>(null);
  const [error, setError] = useState('');
  // "Hoje" no horário do Brasil, vindo do servidor (o relógio do navegador pode estar em outro fuso).
  const [today, setToday] = useState('');
  const load = () => api<AdminNotice[]>('/api/v1/admin/notices').then(setItems).catch(e => setError((e as Error).message));
  useEffect(() => {
    load();
    api<AdminOverview>('/api/v1/admin/clinics').then(o => { setClinics(o.clinics); setToday(o.today); }).catch(() => undefined);
  }, []);

  return (
    <>
      <div className="admin-head">
        <div><h1>Avisos para as clínicas</h1>
          <p className="muted">Mensagens que aparecem no painel dos assinantes: novidades, manutenção, promoções, cobrança.</p></div>
        <button className="primary" onClick={() => setForm({ ...EMPTY, startsOn: today })}><Icon name="plus" size={18} /> Novo aviso</button>
      </div>
      {error && <p className="error-box" role="alert">{error}</p>}
      {items?.length === 0 && <EmptyState icon="bell" title="Nenhum aviso ainda" text="Crie o primeiro para aparecer no painel das clínicas." />}
      <ul className="cards">
        {items?.map(n => (
          <li key={n.id} className={n.active ? 'row-card clickable' : 'row-card clickable inactive'}
              onClick={() => setForm({ id: n.id, title: n.title, body: n.body, level: n.level, display: n.display,
                clinicId: n.clinicId ?? '', startsOn: n.startsOn, endsOn: n.endsOn ?? '', active: n.active })}>
            <div className="grow">
              <strong>{n.title}</strong> <span className={`pill nst-${n.status}`}>{STATUS_LABEL[n.status]}</span>{' '}
              <span className={`pill lv-pill lv-${n.level}`}>{LEVEL_LABEL[n.level]}</span>
              <span className="meta">{n.clinicName ?? 'Todas as clínicas'} · {n.display === 'MODAL' ? 'Janela' : 'Faixa'} ·{' '}
                {n.endsOn ? `${dateBrFull(n.startsOn)} a ${dateBrFull(n.endsOn)}` : `desde ${dateBrFull(n.startsOn)}`}</span>
              <span className="meta">Lido por {n.readers} {n.readers === 1 ? 'pessoa' : 'pessoas'} ({n.clinicsRead} {n.clinicsRead === 1 ? 'clínica' : 'clínicas'})</span>
            </div>
            <Icon name="right" />
          </li>
        ))}
      </ul>
      {form && <NoticeSheet form={form} setForm={setForm} clinics={clinics} onSaved={() => { setForm(null); load(); }} />}
    </>
  );
}

function NoticeSheet({ form, setForm, clinics, onSaved }: {
  form: Form; setForm: (f: Form | null) => void; clinics: AdminClinic[]; onSaved: () => void;
}) {
  const { toast, confirm } = useUi();
  const [error, setError] = useState('');
  const set = (patch: Partial<Form>) => setForm({ ...form, ...patch });

  async function save(event: FormEvent) {
    event.preventDefault();
    setError('');
    const payload = { title: form.title, body: form.body, level: form.level, display: form.display,
      clinicId: form.clinicId || null, startsOn: form.startsOn || null, endsOn: form.endsOn || null, active: form.active };
    try {
      if (form.id) await api(`/api/v1/admin/notices/${form.id}`, 'PUT', payload);
      else await api('/api/v1/admin/notices', 'POST', payload);
      toast(form.id ? 'Aviso salvo' : 'Aviso publicado');
      onSaved();
    } catch (e) { setError((e as Error).message); }
  }
  async function deactivate() {
    if (!await confirm('Desativar este aviso? Ele sai do painel das clínicas e fica no histórico.', { confirmLabel: 'Desativar', danger: true })) return;
    await api(`/api/v1/admin/notices/${form.id}`, 'DELETE');
    toast('Aviso desativado');
    onSaved();
  }

  return (
    <Sheet title={form.id ? 'Editar aviso' : 'Novo aviso'} onClose={() => setForm(null)}>
      <form className="form" onSubmit={save} onInput={() => setError('')}>
        <label>Título<input required maxLength={120} value={form.title} placeholder="Ex.: Novidade: aniversariantes no painel"
                           onChange={e => set({ title: e.target.value })} /></label>
        <label>Mensagem<textarea required maxLength={2000} rows={4} value={form.body}
                                 onChange={e => set({ body: e.target.value })} /></label>
        <fieldset className="field"><legend>Nível</legend>
          <div className="chips">{LEVELS.map(l => (
            <button key={l} type="button" className={form.level === l ? 'chip on' : 'chip'} aria-pressed={form.level === l}
                    onClick={() => set({ level: l })}>{LEVEL_LABEL[l]}</button>))}</div>
        </fieldset>
        <fieldset className="field"><legend>Como aparece</legend>
          <div className="chips">{DISPLAYS.map(d => (
            <button key={d.id} type="button" className={form.display === d.id ? 'chip on' : 'chip'} aria-pressed={form.display === d.id}
                    onClick={() => set({ display: d.id })}>{d.label}</button>))}</div>
          <p className="muted small">{DISPLAYS.find(d => d.id === form.display)!.help}</p>
        </fieldset>
        <label>Para quem
          <select value={form.clinicId} onChange={e => set({ clinicId: e.target.value })}>
            <option value="">Todas as clínicas</option>
            {clinics.map(c => <option key={c.id} value={c.id}>{c.displayName}</option>)}
          </select></label>
        <div className="row">
          <label>A partir de<input type="date" required value={form.startsOn} onChange={e => set({ startsOn: e.target.value })} /></label>
          <label>Sai do ar em (opcional)<input type="date" value={form.endsOn} min={form.startsOn} onChange={e => set({ endsOn: e.target.value })} /></label>
        </div>
        <div className="notice-preview">
          <span className="muted small">Prévia no painel da clínica</span>
          {form.display === 'BANNER'
            ? <NoticeBanner title={form.title} body={form.body} level={form.level} onDismiss={() => undefined} />
            : <div className="notice-preview-modal"><NoticeCard title={form.title} body={form.body} level={form.level} /></div>}
        </div>
        {error && <p className="error-box" role="alert">{error}</p>}
        <div className="sheet-actions">
          {form.id && form.active && <button type="button" className="link-button danger" onClick={deactivate}>Desativar</button>}
          {form.id && !form.active && <button type="button" onClick={() => set({ active: true })}>Reativar ao salvar</button>}
          <button type="button" onClick={() => setForm(null)}>Cancelar</button>
          <button className="primary">{form.id ? 'Salvar' : 'Publicar aviso'}</button>
        </div>
      </form>
    </Sheet>
  );
}
