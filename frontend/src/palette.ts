import { contrastRatio, DEFAULT_THEME, type Theme } from './color.ts';

/**
 * Cores da logo → paletas sugeridas para o tema da clínica. Funções puras: recebem os pixels RGBA
 * (ex.: de um canvas pequeno, 64×64) e não dependem do navegador.
 */

export type Swatch = { hex: string; weight: number; h: number; s: number; l: number };

const clamp = (v: number, min = 0, max = 1) => Math.min(max, Math.max(min, v));

export function toHex(r: number, g: number, b: number): string {
  return '#' + [r, g, b].map(v => Math.round(clamp(v, 0, 255)).toString(16).padStart(2, '0')).join('').toUpperCase();
}

export function hexToRgb(hex: string): [number, number, number] {
  return [1, 3, 5].map(i => parseInt(hex.slice(i, i + 2), 16)) as [number, number, number];
}

/** RGB 0–255 → HSL com h em graus e s, l de 0 a 1. */
export function toHsl(r: number, g: number, b: number): [number, number, number] {
  const [rn, gn, bn] = [r / 255, g / 255, b / 255];
  const max = Math.max(rn, gn, bn);
  const min = Math.min(rn, gn, bn);
  const l = (max + min) / 2;
  if (max === min) return [0, 0, l];
  const d = max - min;
  const s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
  const h = max === rn ? ((gn - bn) / d + (gn < bn ? 6 : 0)) : max === gn ? (bn - rn) / d + 2 : (rn - gn) / d + 4;
  return [h * 60, s, l];
}

export function fromHsl(h: number, s: number, l: number): string {
  const k = (n: number) => (n + h / 30) % 12;
  const a = s * Math.min(l, 1 - l);
  const f = (n: number) => l - a * Math.max(-1, Math.min(k(n) - 3, Math.min(9 - k(n), 1)));
  return toHex(f(0) * 255, f(8) * 255, f(4) * 255);
}

function hsl(hex: string): [number, number, number] {
  return toHsl(...hexToRgb(hex));
}

/** Escurece até o texto/link nessa cor ter contraste mínimo sobre branco (preserva o tom da marca). */
export function readableOnWhite(hex: string, minRatio = 3): string {
  let [h, s, l] = hsl(hex);
  let color = hex;
  while (contrastRatio(color, '#FFFFFF') < minRatio && l > 0.05) {
    l -= 0.03;
    color = fromHsl(h, s, l);
  }
  return color;
}

export function darken(hex: string, amount: number): string {
  const [h, s, l] = hsl(hex);
  return fromHsl(h, s, clamp(l - amount, 0.08, 1));
}

function hueDistance(a: number, b: number): number {
  const d = Math.abs(a - b) % 360;
  return d > 180 ? 360 - d : d;
}

function distance(a: Swatch, b: Swatch): number {
  const [ar, ag, ab] = hexToRgb(a.hex);
  const [br, bg, bb] = hexToRgb(b.hex);
  return Math.hypot(ar - br, ag - bg, ab - bb);
}

/**
 * Cores dominantes da imagem, mais frequentes primeiro. Ignora pixels transparentes e o fundo branco
 * (quase toda logo vem sobre branco) e junta tons muito parecidos.
 */
export function dominantColors(pixels: ArrayLike<number>, max = 6): Swatch[] {
  const buckets = new Map<number, { r: number; g: number; b: number; n: number }>();
  for (let i = 0; i + 3 < pixels.length; i += 4) {
    const [r, g, b, a] = [pixels[i], pixels[i + 1], pixels[i + 2], pixels[i + 3]];
    if (a < 128) continue;
    if (r > 235 && g > 235 && b > 235) continue;
    const key = ((r >> 4) << 8) | ((g >> 4) << 4) | (b >> 4);
    const bucket = buckets.get(key) ?? { r: 0, g: 0, b: 0, n: 0 };
    bucket.r += r; bucket.g += g; bucket.b += b; bucket.n++;
    buckets.set(key, bucket);
  }
  const total = [...buckets.values()].reduce((sum, x) => sum + x.n, 0);
  if (total === 0) return [];

  const swatches = [...buckets.values()]
    .sort((x, y) => y.n - x.n)
    .map(x => {
      const [r, g, b] = [x.r / x.n, x.g / x.n, x.b / x.n];
      const [h, s, l] = toHsl(r, g, b);
      return { hex: toHex(r, g, b), weight: x.n / total, h, s, l };
    });

  const merged: Swatch[] = [];
  for (const swatch of swatches) {
    const near = merged.find(m => distance(m, swatch) < 56);
    if (near) near.weight += swatch.weight;
    else merged.push({ ...swatch });
  }

  // Bordas de traços finos misturam a cor com o fundo branco e podem somar mais pixels que a cor pura.
  // Um tom claro que é exatamente "cor sólida + branco" passa a contar para a cor sólida.
  const solid: Swatch[] = [];
  for (const swatch of [...merged].sort((x, y) => x.l - y.l)) {
    const base = solid.find(b => isTintOf(swatch.hex, b.hex));
    if (base) base.weight += swatch.weight;
    else solid.push(swatch);
  }
  return solid.filter(m => m.weight >= 0.01).sort((x, y) => y.weight - x.weight).slice(0, max);
}

/**
 * tint = base·a + branco·(1 − a), com o mesmo a em todos os canais. Canais em que a base já é quase branca
 * não dizem nada sobre a mistura e são ignorados.
 */
export function isTintOf(tintHex: string, baseHex: string): boolean {
  const tint = hexToRgb(tintHex);
  const base = hexToRgb(baseHex);
  const ratios = [0, 1, 2]
    .filter(i => 255 - base[i] >= 30)
    .map(i => (255 - tint[i]) / (255 - base[i]));
  if (ratios.length < 2) return false;
  const min = Math.min(...ratios);
  const max = Math.max(...ratios);
  return max - min <= 0.12 && min >= 0.05 && max <= 0.95;
}

const isColorful = (c: Swatch) => c.s >= 0.25 && c.l >= 0.12 && c.l <= 0.88;

/**
 * Até 4 paletas (principal, secundária, destaque) a partir das cores da logo. A principal é escurecida se
 * preciso para continuar legível sobre branco; o destaque é a cor de tom mais diferente da principal.
 */
export function suggestPalettes(colors: Swatch[]): Theme[] {
  const colorful = colors.filter(isColorful);
  const darks = colors.filter(c => c.l < 0.3);
  const result: Theme[] = [];
  const add = (primaryHex: string, secondaryHex: string, accentHex: string) => {
    const primary = readableOnWhite(primaryHex);
    const theme = { primary, secondary: readableOnWhite(secondaryHex, 4.5), accent: accentHex.toUpperCase() };
    if (!result.some(t => t.primary === theme.primary && t.secondary === theme.secondary && t.accent === theme.accent)) {
      result.push(theme);
    }
  };
  const accentFor = (base: Swatch) => {
    const others = colorful.filter(c => c !== base).sort((a, b) => hueDistance(b.h, base.h) - hueDistance(a.h, base.h));
    if (others.length > 0 && hueDistance(others[0].h, base.h) >= 30) return others[0].hex;
    return fromHsl((base.h + 180) % 360, 0.8, 0.5);
  };

  if (colorful.length === 0) {
    // Logo sem cor (preta, cinza): marca neutra com destaque padrão.
    const base = darks[0]?.hex ?? colors[0]?.hex ?? DEFAULT_THEME.primary;
    add(base, darken(base, 0.1), DEFAULT_THEME.accent);
    add(DEFAULT_THEME.primary, base, DEFAULT_THEME.accent);
    return result;
  }

  for (const base of colorful.slice(0, 3)) {
    const dark = darks.find(d => d.hex !== base.hex);
    add(base.hex, dark ? dark.hex : darken(base.hex, 0.2), accentFor(base));
  }
  // Variação sóbria: cor escura da logo como principal e a cor viva como destaque.
  if (darks.length > 0) add(darks[0].hex, darken(darks[0].hex, 0.08), colorful[0].hex);
  // Variação com a secundária derivada da própria cor principal.
  add(colorful[0].hex, darken(colorful[0].hex, 0.25), accentFor(colorful[0]));
  return result.slice(0, 4);
}
