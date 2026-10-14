import { useEffect, useState, type CSSProperties } from 'react';
import { api, type ClinicProfile, type Service } from './api';
import { useFonts } from './PublicPage';
import { autoHeadline, classify, FONTS, joinNames, resolveFonts, resolveStyle, STYLES, type FontsId, type StyleId } from './siteStyle';

/** Fontes carregadas só para as amostras desta tela. */
function FontLoader({ id }: { id: FontsId }) {
  useFonts(id);
  return null;
}

/**
 * Aparência do site: estilo (automático pelos serviços ou escolhido), par de fontes e frase de destaque.
 * Altera só o formulário; vale ao salvar.
 */
export function SiteSection({ form, set }: {
  form: ClinicProfile; set: (patch: Partial<ClinicProfile>) => void;
}) {
  const [services, setServices] = useState<Service[]>([]);
  useEffect(() => {
    api<Service[]>('/api/v1/services').then(list => setServices(list.filter(s => s.active))).catch(() => undefined);
  }, []);

  const detected = classify(services, form.about);
  const style: StyleId = resolveStyle(form.siteStyle, services, form.about);
  const fonts: FontsId = resolveFonts(form.siteFonts, style);
  const styleIds = Object.keys(STYLES) as StyleId[];
  const fontIds = Object.keys(FONTS) as FontsId[];

  return (
    <div className="site-section">
      {fontIds.map(id => <FontLoader key={id} id={id} />)}
      <h3>Estilo</h3>
      <p className="muted small">
        O site é montado com a sua logo, as cores e os serviços cadastrados. Escolha a personalidade dele:
      </p>

      <div className="choice-grid" role="radiogroup" aria-label="Estilo do site">
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
      <p className="muted small">
        Vazio = frase automática do estilo. O site usa a fonte <strong>{FONTS[fonts].heading}</strong> nos títulos.
      </p>
    </div>
  );
}
