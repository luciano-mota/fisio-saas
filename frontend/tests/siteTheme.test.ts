import { test } from 'node:test';
import assert from 'node:assert/strict';
import { contrastRatio } from '../src/color.ts';
import { mix, resolveMood, resolveTemplate, siteColors, type MoodId } from '../src/siteTheme.ts';

const THEMES = [
  { primary: '#0F766E', secondary: '#134E4A', accent: '#F59E0B' }, // padrão
  { primary: '#F97316', secondary: '#FDBA74', accent: '#0EA5E9' }, // laranja e secundária clara
  { primary: '#FDE047', secondary: '#FEF9C3', accent: '#A3E635' }, // tudo claro
  { primary: '#111827', secondary: '#000000', accent: '#F43F5E' }, // tudo escuro
  { primary: '#7C3AED', secondary: '#4C1D95', accent: '#F472B6' },
  { primary: '#1D4ED8', secondary: '#1E3A8A', accent: '#F97316' },
  { primary: '#94A3B8', secondary: '#CBD5E1', accent: '#64748B' } // acinzentado de contraste baixo
];

test('mistura de cores', () => {
  assert.equal(mix('#000000', '#FFFFFF', 0.5), '#808080');
  assert.equal(mix('#0F766E', '#0F766E', 0.3), '#0F766E');
});

test('todo texto do site fica legível em qualquer paleta e clima', () => {
  for (const theme of THEMES) {
    for (const mood of ['claro', 'suave', 'escuro'] as MoodId[]) {
      const c = siteColors(theme, mood);
      const where = `${theme.primary}/${theme.secondary} ${mood}`;
      for (const bg of [c.paper, c.paperAlt, c.card]) {
        assert.ok(contrastRatio(c.ink, bg) >= 7, `texto principal ${where}`);
        assert.ok(contrastRatio(c.inkSoft, bg) >= 4.5, `texto secundário ${where}`);
        assert.ok(contrastRatio(c.brand, bg) >= 4.5, `destaques ${where}`);
      }
      assert.ok(contrastRatio(c.cta, c.onCta) >= 4.5, `texto do botão ${where}`);
      assert.ok(contrastRatio(c.cta, c.paper) >= 3, `botão sobre o fundo ${where}`);
      assert.ok(contrastRatio(c.band, c.onBand) >= 4.5, `faixa colorida ${where}`);
      assert.ok(contrastRatio(c.band, c.bandSoft) >= 4.5, `texto suave na faixa ${where}`);
    }
  }
});

test('fundo nunca é branco puro nos climas suave/escuro e o texto não é preto puro', () => {
  const suave = siteColors(THEMES[0], 'suave');
  assert.notEqual(suave.paper, '#FFFFFF');
  assert.notEqual(suave.ink, '#000000');
  assert.ok(contrastRatio(siteColors(THEMES[0], 'escuro').paper, '#000000') < 2, 'escuro é escuro');
});

test('modelo automático pelo estilo e clima padrão', () => {
  assert.equal(resolveTemplate('auto', 'sereno'), 'editorial');
  assert.equal(resolveTemplate(null, 'dinamico'), 'imersivo');
  assert.equal(resolveTemplate('acolhedor', 'clinico'), 'acolhedor');
  assert.equal(resolveTemplate('<script>', 'clinico'), 'classico');
  assert.equal(resolveMood(undefined), 'suave');
  assert.equal(resolveMood('escuro'), 'escuro');
});
