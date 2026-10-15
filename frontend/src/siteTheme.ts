/**
 * Modelos (estrutura) e climas (fundo e texto) do site. As cores de fundo e de texto saem das cores da clínica,
 * nunca branco e preto puros, e cada par é conferido para ficar legível (contraste WCAG).
 */
import { contrastRatio, DEFAULT_THEME, isHex, readableTextOn, type Theme } from './color.ts';
import type { StyleId } from './siteStyle.ts';

export type TemplateId = 'classico' | 'imersivo' | 'editorial' | 'acolhedor';
export type MoodId = 'claro' | 'suave' | 'escuro';

export const TEMPLATES: Record<TemplateId, { label: string; description: string }> = {
  classico: { label: 'Clássico', description: 'Texto e foto lado a lado, direto ao ponto.' },
  imersivo: { label: 'Imersivo', description: 'Foto em tela cheia no topo e seções na cor da clínica.' },
  editorial: { label: 'Editorial', description: 'Visual de revista: título grande, fotos largas, serviços numerados.' },
  acolhedor: { label: 'Acolhedor', description: 'Topo colorido, foto redonda e serviços em cartões.' }
};

export const MOODS: Record<MoodId, { label: string; description: string }> = {
  claro: { label: 'Claro', description: 'Fundo quase branco, limpo.' },
  suave: { label: 'Suave', description: 'Fundo creme, tingido com as suas cores.' },
  escuro: { label: 'Escuro', description: 'Fundo escuro na sua cor, texto claro.' }
};

/** Modelo automático pelo estilo detectado nos serviços. */
const TEMPLATE_BY_STYLE: Record<StyleId, TemplateId> = {
  sereno: 'editorial', dinamico: 'imersivo', acolhedor: 'acolhedor', clinico: 'classico'
};

export function resolveTemplate(chosen: string | null | undefined, style: StyleId): TemplateId {
  return chosen && chosen in TEMPLATES ? chosen as TemplateId : TEMPLATE_BY_STYLE[style];
}

export function resolveMood(chosen: string | null | undefined): MoodId {
  return chosen && chosen in MOODS ? chosen as MoodId : 'suave';
}

function toRgb(hex: string): [number, number, number] {
  return [1, 3, 5].map(i => parseInt(hex.slice(i, i + 2), 16)) as [number, number, number];
}

/** Mistura duas cores; {@code weight} é quanto de {@code b} entra (0 = só a, 1 = só b). */
export function mix(a: string, b: string, weight: number): string {
  const [x, y] = [toRgb(a), toRgb(b)];
  return '#' + x.map((v, i) => Math.round(v + (y[i] - v) * weight).toString(16).padStart(2, '0')).join('').toUpperCase();
}

/** Escurece (ou clareia) aos poucos até ter o contraste mínimo contra todos os fundos informados. */
function adjustUntil(color: string, backgrounds: string[], min: number, towards: '#000000' | '#FFFFFF'): string {
  for (let step = 0; step <= 20; step++) {
    const candidate = mix(color, towards, step / 20);
    if (backgrounds.every(bg => contrastRatio(candidate, bg) >= min)) return candidate;
  }
  return towards;
}

export type SiteColors = {
  paper: string; paperAlt: string; card: string; ink: string; inkSoft: string; line: string;
  brand: string; cta: string; onCta: string; band: string; onBand: string; bandSoft: string;
};

export function siteColors(theme: Partial<Theme> | undefined, mood: MoodId): SiteColors {
  const safe = (v: string | undefined, fallback: string) => (v && isHex(v) ? v.toUpperCase() : fallback);
  const primary = safe(theme?.primary, DEFAULT_THEME.primary);
  const secondary = safe(theme?.secondary, DEFAULT_THEME.secondary);
  const dark = mood === 'escuro';

  let paper: string, paperAlt: string, card: string, ink: string, inkSoft: string, line: string;
  if (dark) {
    paper = mix(secondary, '#0A0E14', 0.78);
    paperAlt = mix(paper, '#FFFFFF', 0.05);
    card = mix(paper, '#FFFFFF', 0.08);
    ink = adjustUntil(mix('#F8FAFC', primary, 0.06), [paper, paperAlt, card], 10, '#FFFFFF');
    inkSoft = adjustUntil(mix('#CBD5E1', primary, 0.12), [paper, paperAlt, card], 4.6, '#FFFFFF');
    line = mix(paper, '#FFFFFF', 0.14);
  } else {
    const base = mood === 'suave' ? '#FBF7F1' : '#FFFFFF';
    paper = mix(base, primary, mood === 'suave' ? 0.035 : 0.012);
    paperAlt = mix(base, primary, mood === 'suave' ? 0.085 : 0.05);
    card = mood === 'suave' ? mix('#FFFDFA', primary, 0.015) : '#FFFFFF';
    // Tinta: a cor secundária quase preta, que conversa com a paleta (no lugar do preto puro).
    ink = adjustUntil(mix(secondary, '#0B1220', 0.6), [paper, paperAlt, card], 10, '#000000');
    inkSoft = adjustUntil(mix(ink, paper, 0.3), [paper, paperAlt, card], 4.6, '#000000');
    line = mix(paper, ink, 0.12);
  }
  const backgrounds = [paper, paperAlt, card];
  const brand = adjustUntil(primary, backgrounds, 4.5, dark ? '#FFFFFF' : '#000000');

  // Botão principal: a cor da clínica, ajustada para destacar do fundo e ter texto legível.
  let cta = adjustUntil(primary, [paper], 3, dark ? '#FFFFFF' : '#000000');
  let onCta = readableTextOn(cta);
  for (let step = 1; contrastRatio(cta, onCta) < 4.5 && step <= 20; step++) {
    cta = mix(cta, onCta === '#FFFFFF' ? '#000000' : '#FFFFFF', 0.05);
    onCta = readableTextOn(cta);
  }

  // Faixa forte (seções coloridas, chamada final, rodapé): escura na cor da clínica.
  const band = dark ? mix(primary, '#000000', 0.35) : mix(secondary, '#0B1220', 0.25);
  const onBand = readableTextOn(band);
  const bandSoft = adjustUntil(mix(onBand, band, 0.25), [band], 4.6, onBand === '#FFFFFF' ? '#FFFFFF' : '#000000');
  return { paper, paperAlt, card, ink, inkSoft, line, brand, cta, onCta, band, onBand, bandSoft };
}

/** Variáveis CSS do site para o modelo/clima. */
export function siteColorVariables(colors: SiteColors): Record<string, string> {
  return {
    '--paper': colors.paper, '--paper-alt': colors.paperAlt, '--card': colors.card, '--ink': colors.ink,
    '--ink-soft': colors.inkSoft, '--line': colors.line, '--brand': colors.brand, '--cta': colors.cta,
    '--on-cta': colors.onCta, '--band': colors.band, '--on-band': colors.onBand, '--band-soft': colors.bandSoft
  };
}
