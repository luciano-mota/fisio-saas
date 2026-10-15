import { useCallback, useEffect, useState, type CSSProperties, type FormEvent, type ReactNode } from 'react';
import { api, logoUrl, uploadPhoto, type ClinicProfile, type Faq, type SitePhoto, type Testimonial } from './api';
import { contrastRatio, readableTextOn, themeVariables } from './color';
import { LogoSection } from './LogoSection';
import { ACCEPT, preparePhoto } from './photoImage';
import { SiteSection } from './SiteSection';
import { Icon, PageHeader, useUi } from './ui';

const MAX_GALLERY = 6;

/**
 * Aba "Site": aparência (logo, cores, estilo) e conteúdo do site público. Textos e aparência valem ao salvar;
 * fotos, depoimentos e perguntas são salvos na hora.
 */
export function SiteTab({ owner, profile, onSaved, onLogoChange }: {
  owner: boolean; profile: ClinicProfile; onSaved: (p: ClinicProfile) => void; onLogoChange: (p: ClinicProfile) => void;
}) {
  const { toast } = useUi();
  const [form, setForm] = useState<ClinicProfile>(profile);
  const [error, setError] = useState('');
  const text = (key: keyof ClinicProfile) => (form[key] ?? '') as string;
  const set = (patch: Partial<ClinicProfile>) => setForm(f => ({ ...f, ...patch }));

  async function save(event: FormEvent) {
    event.preventDefault();
    setError('');
    try {
      const saved = await api<ClinicProfile>('/api/v1/clinic', 'PUT', form);
      setForm(saved);
      onSaved(saved);
      toast('Salvo! O site já mostra as novidades.');
    } catch (e) { setError((e as Error).message); }
  }

  const publicUrl = `${window.location.origin}/c/${profile.slug}`;
  const primaryContrast = contrastRatio(form.colorPrimary, readableTextOn(form.colorPrimary));

  return (
    <section>
      <PageHeader title="Site" subtitle="Aparência e conteúdo da sua página pública.">
        {profile.publicPageEnabled && (
          <a className="button" href={publicUrl} target="_blank" rel="noreferrer"><Icon name="external" size={18} /> Ver meu site</a>
        )}
      </PageHeader>
      {!profile.publicPageEnabled && <p className="notice small">O site está desativado. Ative em “Clínica” → Página pública.</p>}

      <form className="stack" onSubmit={save}>
        <fieldset disabled={!owner} className="stack">
          <div className="card form">
          <LogoSection owner={owner} profile={profile} onLogoChange={onLogoChange}
                       current={{ primary: form.colorPrimary, secondary: form.colorSecondary, accent: form.colorAccent }}
                       onPick={t => set({ colorPrimary: t.primary, colorSecondary: t.secondary, colorAccent: t.accent })} />

          <h3>Cores</h3>
          <p className="muted small">Escolha uma sugestão da logo acima ou ajuste à mão. A principal é a dos botões do site.</p>
          <div className="row colors">
            <label>Principal<input type="color" value={form.colorPrimary} onChange={e => set({ colorPrimary: e.target.value })} /></label>
            <label>Secundária<input type="color" value={form.colorSecondary} onChange={e => set({ colorSecondary: e.target.value })} /></label>
            <label>Destaque<input type="color" value={form.colorAccent} onChange={e => set({ colorAccent: e.target.value })} /></label>
          </div>
          <div className="preview" style={themeVariables({
            primary: form.colorPrimary, secondary: form.colorSecondary, accent: form.colorAccent
          }) as CSSProperties}>
            <div className="preview-hero">
              {profile.logoVersion && <span className="preview-logo"><img src={logoUrl(profile.logoVersion)!} alt="" /></span>}
              {form.displayName || 'Sua clínica'}
            </div>
            <button type="button" className="preview-cta">Agendar</button>
          </div>
          {primaryContrast < 4.5 && <p className="notice small">Essa cor principal tem pouco contraste com o texto dos botões.</p>}
          </div>

          <div className="card form"><SiteSection form={form} set={set} /></div>

          <div className="card form">
          <h3>Conteúdo</h3>
          <p className="muted small">Cada parte só aparece no site se estiver preenchida.</p>
          <label>Sobre você<textarea maxLength={1000} value={text('about')} rows={4}
                                     placeholder="Como você trabalha, para quem, o que o paciente pode esperar."
                                     onChange={e => set({ about: e.target.value })} /></label>
          <label>Formação e especializações<textarea maxLength={600} value={text('siteEducation')} rows={3}
                                                     placeholder={'Uma por linha. Ex.:\nFisioterapia – UFMA\nEspecialização em Pilates clínico'}
                                                     onChange={e => set({ siteEducation: e.target.value })} /></label>
          <div className="row">
            <label>Horários<input maxLength={300} value={text('siteHours')} placeholder="Seg a sex, 7h às 19h; sáb até 12h"
                                  onChange={e => set({ siteHours: e.target.value })} /></label>
            <label>Bairros/cidades atendidos em casa<input maxLength={300} value={text('siteServiceArea')}
                                                          placeholder="Centro, Cohama e Renascença"
                                                          onChange={e => set({ siteServiceArea: e.target.value })} /></label>
          </div>
          <label className="switch"><input type="checkbox" checked={form.siteShowMap}
                                           onChange={e => set({ siteShowMap: e.target.checked })} />
            <span>Mostrar mapa do endereço no site</span></label>
          <label className="switch"><input type="checkbox" checked={form.siteShowTestimonials}
                                           onChange={e => set({ siteShowTestimonials: e.target.checked })} />
            <span>Mostrar depoimentos no site</span></label>
          </div>
        </fieldset>
        {error && <p className="error" role="alert">{error}</p>}
        {owner && <div className="save-bar"><span className="muted small">Logo, cores, estilo e textos valem ao salvar.</span>
          <button className="primary">Salvar</button></div>}
      </form>

      <PhotosSection owner={owner} />

      <div className="card form">
        <h3>Depoimentos</h3>
        <p className="notice small">
          Só cadastre com autorização <strong>por escrito</strong> do paciente e confira a regra de publicidade do
          COFFITO antes de ligar “Mostrar depoimentos no site”. Use só o primeiro nome ou as iniciais.
        </p>
        <ItemsEditor<Testimonial> owner={owner} url="/api/v1/clinic/testimonials" consent
          labels={{ empty: 'Nenhum depoimento cadastrado.', add: 'Novo depoimento', edit: 'Editar depoimento',
            remove: 'Remover este depoimento? Ele sai do site.' }}
          fields={[
            { key: 'authorName', label: 'Nome (ex.: Maria S.)', maxLength: 60 },
            { key: 'text', label: 'Depoimento', maxLength: 500, multiline: true }
          ]}
          render={t => <><p>“{t.text}”</p><strong className="small">— {t.authorName}</strong></>} />
      </div>

      <div className="card form">
        <h3>Perguntas frequentes</h3>
        <p className="muted small">Ex.: “Precisa de pedido médico?”, “Atende em casa?”, “Quanto dura a sessão?”.</p>
        <ItemsEditor<Faq> owner={owner} url="/api/v1/clinic/faqs"
          labels={{ empty: 'Nenhuma pergunta cadastrada.', add: 'Nova pergunta', edit: 'Editar pergunta',
            remove: 'Remover esta pergunta? Ela sai do site.' }}
          fields={[
            { key: 'question', label: 'Pergunta', maxLength: 200 },
            { key: 'answer', label: 'Resposta', maxLength: 800, multiline: true }
          ]}
          render={f => <><strong>{f.question}</strong><p className="muted small">{f.answer}</p></>} />
      </div>
    </section>
  );
}

// ---------- Fotos ----------

function PhotosSection({ owner }: { owner: boolean }) {
  const { toast, confirm } = useUi();
  const [photos, setPhotos] = useState<SitePhoto[]>([]);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState<{ kind: 'COVER' | 'GALLERY'; message: string } | null>(null);
  // Prévia local enquanto a foto é preparada e enviada (aparece na hora, antes da resposta do servidor).
  const [pending, setPending] = useState<{ kind: 'COVER' | 'GALLERY'; urls: string[] } | null>(null);
  const load = useCallback(() => api<SitePhoto[]>('/api/v1/clinic/photos').then(setPhotos), []);
  useEffect(() => { load().catch(() => undefined); }, [load]);

  const cover = photos.find(p => p.kind === 'COVER');
  const gallery = photos.filter(p => p.kind === 'GALLERY');

  async function run(label: string, action: () => Promise<unknown>, kind: 'COVER' | 'GALLERY' = 'GALLERY') {
    setBusy(label);
    setError(null);
    try {
      await action();
    } catch (e) {
      setError({ kind, message: (e as Error).message });
    } finally {
      setBusy('');
      await load().catch(() => undefined);
    }
  }

  function send(files: FileList | null, kind: 'COVER' | 'GALLERY') {
    const list = Array.from(files ?? []).slice(0, kind === 'COVER' ? 1 : MAX_GALLERY - gallery.length);
    if (!list.length) return;
    const urls = list.map(file => URL.createObjectURL(file));
    setPending({ kind, urls });
    run('Enviando…', async () => {
      try {
        for (const file of list) await uploadPhoto(await preparePhoto(file), kind);
        toast(kind === 'COVER' ? 'Capa enviada' : list.length === 1 ? 'Foto enviada' : `${list.length} fotos enviadas`);
      } finally {
        setPending(null);
        urls.forEach(url => URL.revokeObjectURL(url));
      }
    }, kind);
  }
  const coverPreview = pending?.kind === 'COVER' ? pending.urls[0] : null;

  async function remove(photo: SitePhoto) {
    if (!await confirm('Remover esta foto? Ela é apagada e some do site.', { confirmLabel: 'Remover foto', danger: true })) return;
    run('Removendo…', async () => { await api(`/api/v1/clinic/photos/${photo.id}`, 'DELETE'); toast('Foto removida'); }, photo.kind);
  }

  return (
    <div className="card form photos">
      <h3>Fotos</h3>
      <p className="muted small">
        Fotos reais passam confiança: você atendendo, o espaço, os aparelhos. Ao enviar, a foto é reduzida e
        perde os dados de localização da câmera. Só publique foto de paciente com autorização por escrito.
      </p>

      <h4>Capa</h4>
      <div className="photo-cover">
        <div className={coverPreview ? 'photo-frame cover sending' : 'photo-frame cover'}>
          {coverPreview ? <><img src={coverPreview} alt="" /><span className="sending-label">Enviando…</span></>
            : cover ? <img src={`/api/v1/clinic/photos/${cover.id}/image`} alt="Foto de capa" />
              : <span className="muted small">Sem capa</span>}
        </div>
        {owner && (
          <div className="actions">
            <label className={busy ? 'button disabled' : 'button primary-link'}>
              {cover ? 'Trocar capa' : 'Enviar capa'}
              <input type="file" accept={ACCEPT} hidden disabled={!!busy}
                     onChange={e => { send(e.target.files, 'COVER'); e.target.value = ''; }} />
            </label>
            {cover && <button type="button" className="danger" onClick={() => remove(cover)}>Remover</button>}
          </div>
        )}
      </div>
      {error?.kind === 'COVER' && <p className="error-box" role="alert">{error.message}</p>}
      <p className="muted small">Aparece no topo do site. De preferência na vertical, com você ou o espaço.</p>

      <h4>Galeria <span className="muted small">({gallery.length} de {MAX_GALLERY})</span></h4>
      {gallery.length === 0 && <p className="muted small">Nenhuma foto na galeria.</p>}
      <ul className="photo-grid">
        {gallery.map((photo, i) => (
          <li key={photo.id}>
            <div className="photo-frame"><img src={`/api/v1/clinic/photos/${photo.id}/image`} alt={photo.caption ?? `Foto ${i + 1}`} /></div>
            {owner ? (
              <>
                <input aria-label={`Legenda da foto ${i + 1}`} maxLength={140} placeholder="Legenda (opcional)"
                       defaultValue={photo.caption ?? ''}
                       onBlur={e => {
                         if ((photo.caption ?? '') !== e.target.value.trim()) {
                           run('Salvando…', () => api(`/api/v1/clinic/photos/${photo.id}`, 'PUT', { caption: e.target.value }));
                         }
                       }} />
                <div className="actions">
                  <button type="button" disabled={i === 0 || !!busy} aria-label="Mover para a esquerda"
                          onClick={() => run('Movendo…', () => api(`/api/v1/clinic/photos/${photo.id}/move`, 'POST', { direction: 'up' }))}>←</button>
                  <button type="button" disabled={i === gallery.length - 1 || !!busy} aria-label="Mover para a direita"
                          onClick={() => run('Movendo…', () => api(`/api/v1/clinic/photos/${photo.id}/move`, 'POST', { direction: 'down' }))}>→</button>
                  <button type="button" className="danger" disabled={!!busy} onClick={() => remove(photo)}>Remover</button>
                </div>
              </>
            ) : photo.caption && <p className="small">{photo.caption}</p>}
          </li>
        ))}
      </ul>
      {owner && gallery.length < MAX_GALLERY && (
        <div className="actions">
          <label className={busy ? 'button disabled' : 'button primary-link'}>
            Adicionar fotos
            <input type="file" accept={ACCEPT} multiple hidden disabled={!!busy}
                   onChange={e => { send(e.target.files, 'GALLERY'); e.target.value = ''; }} />
          </label>
        </div>
      )}
      {pending?.kind === 'GALLERY' && (
        <ul className="photo-grid">{pending.urls.map(url => (
          <li key={url}><div className="photo-frame sending"><img src={url} alt="" /><span className="sending-label">Enviando…</span></div></li>
        ))}</ul>
      )}
      {busy && !pending && <p className="muted small" role="status">{busy}</p>}
      {error?.kind === 'GALLERY' && <p className="error-box" role="alert">{error.message}</p>}
    </div>
  );
}

// ---------- Depoimentos e perguntas (lista ordenável) ----------

type Field<T> = { key: keyof T & string; label: string; maxLength: number; multiline?: boolean };

type Labels = { empty: string; add: string; edit: string; remove: string };

function ItemsEditor<T extends { id: string }>({ owner, url, labels, fields, render, consent }: {
  owner: boolean; url: string; labels: Labels; fields: Field<T>[]; render: (item: T) => ReactNode; consent?: boolean;
}) {
  const { toast, confirm } = useUi();
  const empty = Object.fromEntries(fields.map(f => [f.key, ''])) as Record<string, string>;
  const [items, setItems] = useState<T[]>([]);
  const [draft, setDraft] = useState<Record<string, string>>(empty);
  const [editing, setEditing] = useState<string | null>(null);
  const [agreed, setAgreed] = useState(false);
  const [error, setError] = useState('');
  const load = useCallback(() => api<T[]>(url).then(setItems), [url]);
  useEffect(() => { load().catch(() => undefined); }, [load]);

  async function act(action: () => Promise<unknown>) {
    setError('');
    try {
      await action();
      await load();
      return true;
    } catch (e) {
      setError((e as Error).message);
      return false;
    }
  }

  async function save(event: FormEvent) {
    event.preventDefault();
    const ok = await act(() => editing
      ? api(`${url}/${editing}`, 'PUT', draft)
      : api(url, 'POST', consent ? { ...draft, consent: agreed } : draft));
    if (ok) { toast(editing ? 'Alteração salva' : 'Adicionado'); setDraft(empty); setEditing(null); setAgreed(false); }
  }

  return (
    <>
      <ul className="list">
        {items.length === 0 && <li className="muted small">{labels.empty}</li>}
        {items.map((item, i) => (
          <li key={item.id} className="item site-item">
            <div>{render(item)}</div>
            {owner && (
              <div className="actions">
                <button type="button" disabled={i === 0} aria-label="Subir"
                        onClick={() => act(() => api(`${url}/${item.id}/move`, 'POST', { direction: 'up' }))}>↑</button>
                <button type="button" disabled={i === items.length - 1} aria-label="Descer"
                        onClick={() => act(() => api(`${url}/${item.id}/move`, 'POST', { direction: 'down' }))}>↓</button>
                <button type="button" onClick={() => {
                  setEditing(item.id);
                  setDraft(Object.fromEntries(fields.map(f => [f.key, String(item[f.key] ?? '')])));
                }}>Editar</button>
                <button type="button" className="danger" onClick={async () => {
                  if (await confirm(labels.remove, { confirmLabel: 'Remover', danger: true })) act(() => api(`${url}/${item.id}`, 'DELETE'));
                }}>Remover</button>
              </div>
            )}
          </li>
        ))}
      </ul>
      {owner && (
        <form className="form" onSubmit={save}>
          <h4>{editing ? labels.edit : labels.add}</h4>
          {fields.map(f => (
            <label key={f.key}>{f.label}
              {f.multiline
                ? <textarea required maxLength={f.maxLength} rows={3} value={draft[f.key]}
                            onChange={e => setDraft({ ...draft, [f.key]: e.target.value })} />
                : <input required maxLength={f.maxLength} value={draft[f.key]}
                         onChange={e => setDraft({ ...draft, [f.key]: e.target.value })} />}
            </label>
          ))}
          {consent && !editing && (
            <label className="check"><input type="checkbox" required checked={agreed} onChange={e => setAgreed(e.target.checked)} />
              Tenho autorização por escrito do paciente para publicar</label>
          )}
          {error && <p className="error" role="alert">{error}</p>}
          <div className="actions">
            <button className="primary">{editing ? 'Salvar alterações' : 'Adicionar'}</button>
            {editing && <button type="button" onClick={() => { setEditing(null); setDraft(empty); }}>Cancelar</button>}
          </div>
        </form>
      )}
    </>
  );
}
