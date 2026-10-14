import { test } from 'node:test';
import assert from 'node:assert/strict';
import { contrastRatio } from '../src/color.ts';
import { dominantColors, fromHsl, suggestPalettes, toHsl } from '../src/palette.ts';

/** Imagem sintética: lista de [cor RGBA, quantidade de pixels]. */
function pixels(parts: [[number, number, number, number], number][]): number[] {
  return parts.flatMap(([rgba, n]) => Array.from({ length: n }, () => rgba).flat());
}

const BRANCO: [number, number, number, number] = [255, 255, 255, 255];
const TRANSPARENTE: [number, number, number, number] = [0, 200, 0, 0];
const AZUL: [number, number, number, number] = [29, 78, 216, 255];
const LARANJA: [number, number, number, number] = [249, 115, 22, 255];
const AZUL_ESCURO: [number, number, number, number] = [15, 23, 42, 255];

test('HSL ida e volta', () => {
  const [h, s, l] = toHsl(29, 78, 216);
  assert.equal(fromHsl(h, s, l), '#1D4ED8');
});

test('ignora fundo branco e transparência e ordena pelas cores mais presentes', () => {
  const colors = dominantColors(pixels([[BRANCO, 3000], [TRANSPARENTE, 500], [AZUL, 600], [LARANJA, 200]]));
  assert.equal(colors.length, 2);
  assert.equal(colors[0].hex, '#1D4ED8');
  assert.equal(colors[1].hex, '#F97316');
  assert.ok(Math.abs(colors[0].weight - 0.75) < 0.01);
});

test('tons quase iguais (antialiasing) viram uma cor só', () => {
  const colors = dominantColors(pixels([[AZUL, 500], [[33, 82, 220, 255], 100], [[25, 74, 210, 255], 100]]));
  assert.equal(colors.length, 1);
});

test('logo azul e laranja: principal azul, destaque laranja, tudo legível', () => {
  const palettes = suggestPalettes(dominantColors(pixels([[BRANCO, 2000], [AZUL, 600], [LARANJA, 200], [AZUL_ESCURO, 150]])));
  assert.ok(palettes.length >= 2 && palettes.length <= 4);
  assert.equal(palettes[0].primary, '#1D4ED8');
  assert.equal(palettes[0].accent, '#F97316');
  assert.equal(palettes[0].secondary, '#0F172A');
  for (const p of palettes) {
    assert.ok(contrastRatio(p.primary, '#FFFFFF') >= 3, p.primary);
    assert.ok(contrastRatio(p.secondary, '#FFFFFF') >= 4.5, p.secondary);
  }
  // Alguma sugestão usa a laranja como principal (escurecida para ficar legível sobre branco).
  assert.ok(palettes.some(p => toHsl(...hex(p.primary))[0] > 15 && toHsl(...hex(p.primary))[0] < 35));
});

test('logo de uma cor só ganha destaque complementar', () => {
  const [p] = suggestPalettes(dominantColors(pixels([[BRANCO, 1000], [AZUL, 500]])));
  assert.equal(p.primary, '#1D4ED8');
  const accentHue = toHsl(...hex(p.accent))[0];
  assert.ok(accentHue > 20 && accentHue < 60, `destaque ${p.accent}`);
});

test('logo preta usa tom neutro e destaque padrão', () => {
  const palettes = suggestPalettes(dominantColors(pixels([[BRANCO, 1000], [[20, 20, 20, 255], 400]])));
  assert.equal(palettes[0].primary, '#141414');
  assert.equal(palettes[0].accent, '#F59E0B');
});

test('imagem vazia (toda branca ou transparente) não sugere nada', () => {
  assert.deepEqual(dominantColors(pixels([[BRANCO, 100], [TRANSPARENTE, 100]])), []);
});

function hex(value: string): [number, number, number] {
  return [1, 3, 5].map(i => parseInt(value.slice(i, i + 2), 16)) as [number, number, number];
}

test('tons claros de antialiasing (traço fino sobre branco) contam para a cor sólida', () => {
  const TEAL: [number, number, number, number] = [14, 116, 144, 255];
  const colors = dominantColors(pixels([
    [BRANCO, 2000], [TEAL, 600], [[84, 156, 175, 255], 120], // borda da cor principal
    [[252, 211, 182, 255], 150], [[251, 170, 120, 255], 60], [LARANJA, 100] // texto laranja fino
  ]));
  assert.deepEqual(colors.map(c => c.hex), ['#0E7490', '#F97316']);
  const [p] = suggestPalettes(colors);
  assert.equal(p.primary, '#0E7490');
  assert.equal(p.accent, '#F97316');
});

test('cor clara diferente (azul-claro com marinho) não é confundida com borda', () => {
  const colors = dominantColors(pixels([[BRANCO, 1000], [[15, 23, 42, 255], 300], [[147, 197, 253, 255], 200]]));
  assert.deepEqual(colors.map(c => c.hex), ['#0F172A', '#93C5FD']);
});
