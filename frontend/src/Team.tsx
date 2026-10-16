import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { api, uploadProfessionalPhoto, type Professional, type TeamSettings } from './api';
import { avatarInitials } from './format';
import { ACCEPT, preparePhoto } from './photoImage';
import { EmptyState, Icon, Menu, Sheet, useUi } from './ui';

/** Profissões sugeridas (a clínica pode digitar outra). */
export const PROFESSIONS = ['Fisioterapeuta', 'Nutricionista', 'Psicólogo(a)', 'Fonoaudiólogo(a)', 'Terapeuta ocupacional',
  'Educador(a) físico(a)', 'Massoterapeuta', 'Enfermeiro(a)', 'Médico(a)'];
/** Conselho sugerido pela profissão, para ajudar no preenchimento do registro. */
export function councilHint(profession: string): string {
  const p = profession.toLowerCase();
  if (p.startsWith('fisio') || p.startsWith('terapeuta ocup')) return 'Ex.: CREFITO-8 123456-F';
  if (p.startsWith('nutri')) return 'Ex.: CRN-5 12345';
  if (p.startsWith('psic')) return 'Ex.: CRP 06/123456';
  if (p.startsWith('fono')) return 'Ex.: CRFa 2-12345';
  if (p.startsWith('educador')) return 'Ex.: CREF 012345-G/MA';
  if (p.startsWith('enferm')) return 'Ex.: COREN-MA 123456';
  if (p.startsWith('médic') || p.startsWith('medic')) return 'Ex.: CRM-MA 12345';
  return 'Registro no conselho (opcional)';
}

export function professionalPhotoUrl(p: { id: string; photoVersion: number | null }, slug?: string): string | null {
  if (!p.photoVersion) return null;
  return (slug ? `/api/v1/public/clinics/${slug}/professionals/${p.id}/photo` : `/api/v1/professionals/${p.id}/photo`) + `?v=${p.photoVersion}`;
}

type Form = { id?: string; fullName: string; profession: string; council: string; bio: string; showOnSite: boolean };
const EMPTY: Form = { fullName: '', profession: '', council: '', bio: '', showOnSite: true };

/** Catálogo → Equipe: profissionais de várias áreas, com foto opcional e o que aparece no site. */
export function TeamList({ owner }: { owner: boolean }) {
  const { toast, confirm } = useUi();
  const [items, setItems] = useState<Professional[] | null>(null);
  const [settings, setSettings] = useState<TeamSettings | null>(null);
  const [form, setForm] = useState<Form | null>(null);
  const [error, setError] = useState('');
  const fileFor = useRef<string | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const load = useCallback(() => api<Professional[]>('/api/v1/professionals').then(setItems).catch(e => setError((e as Error).message)), []);
  useEffect(() => {
    load();
    api<TeamSettings>('/api/v1/professionals/settings').then(setSettings).catch(() => undefined);
  }, [load]);

  async function save(event: FormEvent) {
    event.preventDefault();
    if (!form) return;
    try {
      const payload = { fullName: form.fullName, profession: form.profession, council: form.council || null, bio: form.bio || null,
        showOnSite: form.showOnSite };
      if (form.id) await api(`/api/v1/professionals/${form.id}`, 'PUT', payload);
      else await api('/api/v1/professionals', 'POST', payload);
      toast(form.id ? 'Profissional salvo' : 'Profissional adicionado');
      setForm(null);
      load();
    } catch (e) { setError((e as Error).message); }
  }
  async function saveSettings(patch: Partial<TeamSettings>) {
    if (!settings) return;
    try { setSettings(await api<TeamSettings>('/api/v1/professionals/settings', 'PUT', { ...settings, ...patch })); toast('Site atualizado'); }
    catch (e) { setError((e as Error).message); }
  }
  function pickPhoto(id: string) { fileFor.current = id; fileInput.current?.click(); }
  async function onFile(file: File | undefined) {
    const id = fileFor.current;
    if (!file || !id) return;
    try {
      await uploadProfessionalPhoto(id, await preparePhoto(file));
      toast('Foto salva');
      load();
    } catch (e) { setError((e as Error).message); } finally { if (fileInput.current) fileInput.current.value = ''; }
  }
  async function removePhoto(p: Professional) {
    if (!await confirm(`Remover a foto de ${p.fullName}? Ela é apagada de vez.`, { confirmLabel: 'Remover foto', danger: true })) return;
    await api(`/api/v1/professionals/${p.id}/photo`, 'DELETE');
    toast('Foto removida');
    load();
  }
  async function deactivate(p: Professional) {
    if (!await confirm(`Desativar ${p.fullName}? Sai do site; o histórico continua.`, { confirmLabel: 'Desativar', danger: true })) return;
    await api(`/api/v1/professionals/${p.id}`, 'DELETE');
    toast('Profissional desativado');
    load();
  }
  async function reactivate(p: Professional) {
    await api(`/api/v1/professionals/${p.id}`, 'PUT', { ...p, active: true });
    load();
  }
  async function move(p: Professional, direction: 'up' | 'down') {
    await api(`/api/v1/professionals/${p.id}/move`, 'POST', { direction });
    load();
  }

  return (
    <>
      <p className="muted small">Cadastre quem atende na clínica, de qualquer área da saúde. Eles aparecem no site em "Nossa equipe"
        e podem ser indicados em cada serviço.</p>
      {settings && (
        <div className="card team-settings">
          <label className="switch"><input type="checkbox" disabled={!owner} checked={settings.showTeam}
                                           onChange={e => saveSettings({ showTeam: e.target.checked })} />
            <span>Mostrar a seção "Nossa equipe" no site</span></label>
          <label className="switch"><input type="checkbox" disabled={!owner || !settings.showTeam} checked={settings.showPhotos}
                                           onChange={e => saveSettings({ showPhotos: e.target.checked })} />
            <span>Mostrar as fotos da equipe (slides)</span></label>
        </div>
      )}
      {owner && (
        <div className="list-toolbar">
          <button className="primary" onClick={() => { setError(''); setForm(EMPTY); }}><Icon name="plus" size={18} /> Profissional</button>
        </div>
      )}
      {!form && error && <p className="error-box" role="alert">{error}</p>}
      {items?.length === 0 && (
        <EmptyState icon="users" title="Nenhum profissional ainda"
                    text="Ex.: fisioterapeutas, nutricionista, psicóloga. A foto é opcional.">
          {owner && <button className="primary" onClick={() => setForm(EMPTY)}>Cadastrar o primeiro</button>}
        </EmptyState>
      )}
      <ul className="cards">
        {items?.map((p, i) => {
          const photo = professionalPhotoUrl(p);
          return (
            <li key={p.id} className={p.active ? 'row-card' : 'row-card inactive'}>
              {photo ? <img className="team-thumb" src={photo} alt="" /> : <span className="avatar team-thumb">{avatarInitials(p.fullName)}</span>}
              <div className="grow">
                <strong>{p.fullName}</strong>{!p.active && <span className="pill">desativado</span>}
                {p.active && !p.showOnSite && <span className="pill">fora do site</span>}
                <span className="meta">{[p.profession, p.council].filter(Boolean).join(' · ')}</span>
              </div>
              {owner && (
                <Menu label={`Ações de ${p.fullName}`} items={[
                  p.active && { label: 'Editar', icon: 'edit', onClick: () => { setError(''); setForm({ id: p.id, fullName: p.fullName,
                    profession: p.profession, council: p.council ?? '', bio: p.bio ?? '', showOnSite: p.showOnSite }); } },
                  p.active && { label: photo ? 'Trocar foto' : 'Adicionar foto', icon: 'plus', onClick: () => pickPhoto(p.id) },
                  p.active && !!photo && { label: 'Remover foto', icon: 'trash', onClick: () => removePhoto(p) },
                  p.active && i > 0 && { label: 'Subir', icon: 'left', onClick: () => move(p, 'up') },
                  p.active && i < (items?.length ?? 0) - 1 && { label: 'Descer', icon: 'right', onClick: () => move(p, 'down') },
                  p.active && { label: 'Desativar', icon: 'trash', danger: true, onClick: () => deactivate(p) },
                  !p.active && { label: 'Reativar', icon: 'repeat', onClick: () => reactivate(p) }
                ]} />
              )}
            </li>
          );
        })}
      </ul>
      <input ref={fileInput} type="file" accept={ACCEPT} hidden onChange={e => onFile(e.target.files?.[0])} />

      {form && (
        <Sheet title={form.id ? 'Editar profissional' : 'Novo profissional'} onClose={() => setForm(null)}>
          <form className="form" onSubmit={save} onInput={() => setError('')}>
            <label>Nome<input required maxLength={120} value={form.fullName} placeholder="Ex.: Dra. Paula Nunes"
                              onChange={e => setForm({ ...form, fullName: e.target.value })} /></label>
            <label>Profissão<input required maxLength={60} list="professions" value={form.profession} placeholder="Ex.: Nutricionista"
                                   onChange={e => setForm({ ...form, profession: e.target.value })} /></label>
            <datalist id="professions">{PROFESSIONS.map(p => <option key={p} value={p} />)}</datalist>
            <label>Registro no conselho (opcional)<input maxLength={40} value={form.council} placeholder={councilHint(form.profession)}
                                                         onChange={e => setForm({ ...form, council: e.target.value })} /></label>
            <label>Apresentação curta (opcional)<textarea maxLength={600} rows={3} value={form.bio}
                                                          placeholder="Ex.: Especialista em nutrição esportiva. Atende adultos e idosos."
                                                          onChange={e => setForm({ ...form, bio: e.target.value })} /></label>
            <label className="switch"><input type="checkbox" checked={form.showOnSite} onChange={e => setForm({ ...form, showOnSite: e.target.checked })} />
              <span>Mostrar no site</span></label>
            <p className="muted small">A foto é opcional: depois de salvar, use "Adicionar foto" no menu do profissional.</p>
            {error && <p className="error-box" role="alert">{error}</p>}
            <div className="sheet-actions"><button type="button" onClick={() => setForm(null)}>Cancelar</button>
              <button className="primary">{form.id ? 'Salvar' : 'Adicionar'}</button></div>
          </form>
        </Sheet>
      )}
    </>
  );
}
