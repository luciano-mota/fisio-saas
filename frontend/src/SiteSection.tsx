import { useEffect, useState, type CSSProperties } from 'react';
import { api, type ClinicProfile, type Service, type SitePhoto } from './api';
import { useFonts } from './PublicPage';
import { autoHeadline, classify, FONTS, joinNames, resolveFonts, resolveStyle, STYLES, type FontsId, type StyleId } from './siteStyle';
import { MOODS, resolveMood, resolveTemplate, siteColors, siteColorVariables, TEMPLATES, type MoodId,
  type TemplateId } from './siteTheme';
import { Icon } from './ui';

/** Fontes carregadas só para as amostras desta tela. */
function FontLoader({ id }: { id: FontsId }) {
  useFonts(id);
  return null;
}

/** Miniatura do modelo desenhada com as cores (e a capa) da própria clínica. */
function Thumb({ template, cover }: { template: TemplateId; cover: string | null }) {
  const photo = cover ? { backgroundImage: `url(${cover})` } : undefined;
  return (
    <span className={`thumb thumb-${template}`} aria-hidden="true">
      <i className="t-nav" />
      {template === 'classico' && <><span className="t-text"><b /><b /><em /></span><i className="t-photo" style={photo} /></>}
      {template === 'imersivo' && <i className="t-photo full" style={photo}><span className="t-text light"><b /><b /><em /></span></i>}
      {template === 'editorial' && <><span className="t-text center"><b /><b /></span><i className="t-photo wide" style={photo} /><span className="t-rows"><b /><b /></span></>}
      {template === 'acolhedor' && <span className="t-band"><span className="t-text light"><b /><b /><em /></span><i className="t-photo round" style={photo} /></span>}
    </span>
  );
}

/**
 * Aparência do site: modelo (estrutura), clima (fundo e texto), fontes e frase de destaque. A "personalidade" (textos
 * automáticos e detalhes) vem dos serviços e pode ser trocada em ajustes finos. Altera só o formulário; vale ao salvar.
 */
export function SiteSection({ form, set }: {
  form: ClinicProfile; set: (patch: Partial<ClinicProfile>) => void;
}) {
  const [services, setServices] = useState<Service[]>([]);
  const [cover, setCover] = useState<string | null>(null);
  useEffect(() => {
    api<Service[]>('/api/v1/services').then(list => setServices(list.filter(s => s.active))).catch(() => undefined);
    api<SitePhoto[]>('/api/v1/clinic/photos')
      .then(list => { const c = list.find(p => p.kind === 'COVER'); setCover(c ? `/api/v1/clinic/photos/${c.id}/image` : null); })
      .catch(() => undefined);
  }, []);

  const detected = classify(services, form.about);
  const style: StyleId = resolveStyle(form.siteStyle, services, form.about);
  const fonts: FontsId = resolveFonts(form.siteFonts, style);
  const template: TemplateId = resolveTemplate(form.siteTemplate, style);
  const mood: MoodId = resolveMood(form.siteMood);
  const colors = siteColorVariables(siteColors({ primary: form.colorPrimary, secondary: form.colorSecondary, accent: form.colorAccent }, mood));
  const styleIds = Object.keys(STYLES) as StyleId[];
  const fontIds = Object.keys(FONTS) as FontsId[];
  const templateIds = Object.keys(TEMPLATES) as TemplateId[];
  const preview = `/c/${form.slug}?modelo=${template}&clima=${mood}&estilo=${style}&fontes=${fonts}`;

  return (
    <div className="site-section" style={colors as CSSProperties}>
      {fontIds.map(id => <FontLoader key={id} id={id} />)}
      <div className="section-row">
        <h3>Modelo do site</h3>
        <a className="button small-button" href={preview} target="_blank" rel="noreferrer">
          <Icon name="external" size={16} /> Ver prévia</a>
      </div>
      <p className="muted small">Cada modelo organiza o site de um jeito. A prévia abre o site com o que está escolhido aqui, antes de salvar.</p>

      <div className="choice-grid templates" role="radiogroup" aria-label="Modelo do site">
        <button type="button" role="radio" aria-checked={form.siteTemplate === 'auto'}
                className={form.siteTemplate === 'auto' ? 'choice on' : 'choice'} onClick={() => set({ siteTemplate: 'auto' })}>
          <Thumb template={resolveTemplate('auto', style)} cover={cover} />
          <strong>Automático</strong>
          <span>{TEMPLATES[resolveTemplate('auto', style)].label}, pelos seus serviços</span>
        </button>
        {templateIds.map(id => (
          <button type="button" role="radio" key={id} aria-checked={form.siteTemplate === id}
                  className={form.siteTemplate === id ? 'choice on' : 'choice'} onClick={() => set({ siteTemplate: id })}>
            <Thumb template={id} cover={cover} />
            <strong>{TEMPLATES[id].label}</strong>
            <span>{TEMPLATES[id].description}</span>
          </button>
        ))}
      </div>

      <p className="small"><strong>Clima</strong> — fundo e cor do texto, sempre tirados das suas cores.</p>
      <div className="choice-grid moods" role="radiogroup" aria-label="Clima do site">
        {(Object.keys(MOODS) as MoodId[]).map(id => {
          const c = siteColorVariables(siteColors({ primary: form.colorPrimary, secondary: form.colorSecondary, accent: form.colorAccent }, id));
          return (
            <button type="button" role="radio" key={id} aria-checked={mood === id}
                    className={mood === id ? 'choice on' : 'choice'} onClick={() => set({ siteMood: id })}>
              <span className="mood-swatch" style={c as CSSProperties}><b>Aa</b><i /></span>
              <strong>{MOODS[id].label}</strong>
              <span>{MOODS[id].description}</span>
            </button>
          );
        })}
      </div>

      <p className="small"><strong>Fontes</strong> — automático usa a do estilo ({FONTS[STYLES[style].fonts].label}).</p>
      <div className="choice-grid fonts" role="radiogroup" aria-label="Fontes do site">
        <button type="button" role="radio" aria-checked={form.siteFonts === 'auto'}
                className={form.siteFonts === 'auto' ? 'choice on' : 'choice'} onClick={() => set({ siteFonts: 'auto' })}>
          <strong>Automático</strong><span>{FONTS[STYLES[style].fonts].label}</span>
        </button>
        {fontIds.map(id => (
          <button type="button" role="radio" key={id} aria-checked={form.siteFonts === id}
                  className={form.siteFonts === id ? 'choice on' : 'choice'} onClick={() => set({ siteFonts: id })}
                  style={{ '--sample-heading': `'${FONTS[id].heading}'`, '--sample-body': `'${FONTS[id].body}'` } as CSSProperties}>
            <span className="font-sample">Aa Fisioterapia</span>
            <span className="font-body-sample">{FONTS[id].label} · {FONTS[id].heading}</span>
          </button>
        ))}
      </div>

      <label>Frase de destaque (opcional)
        <input maxLength={140} value={form.siteHeadline ?? ''} placeholder={autoHeadline(style, services)}
               onChange={e => set({ siteHeadline: e.target.value })} />
      </label>
      <p className="muted small">Vazio = frase automática. O site usa a fonte <strong>{FONTS[fonts].heading}</strong> nos títulos.</p>

      <details className="fine-tune">
        <summary>Ajustes finos: personalidade dos textos ({STYLES[style].label})</summary>
        <p className="muted small">Define a frase automática, o subtítulo e pequenos detalhes de forma.</p>
        <div className="choice-grid" role="radiogroup" aria-label="Personalidade do site">
          <button type="button" role="radio" aria-checked={form.siteStyle === 'auto'}
                  className={form.siteStyle === 'auto' ? 'choice on' : 'choice'} onClick={() => set({ siteStyle: 'auto' })}>
            <strong>Automático</strong>
            <span>
              {STYLES[detected.style].label}
              {detected.reasons.length > 0 ? ` — por causa de ${joinNames(detected.reasons.slice(0, 3))}` : ' — neutro, sem pistas nos serviços'}
            </span>
          </button>
          {styleIds.map(id => (
            <button type="button" role="radio" key={id} aria-checked={form.siteStyle === id}
                    className={form.siteStyle === id ? 'choice on' : 'choice'} onClick={() => set({ siteStyle: id })}>
              <strong>{STYLES[id].label}</strong>
              <span>{STYLES[id].description}</span>
            </button>
          ))}
        </div>
      </details>
    </div>
  );
}
