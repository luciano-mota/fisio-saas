import { test } from 'node:test';
import assert from 'node:assert/strict';
import { contrastRatio, readableTextOn, themeVariables } from '../src/color.ts';

test('contraste preto x branco é 21:1', () => {
  assert.equal(Math.round(contrastRatio('#000000', '#FFFFFF')), 21);
});

test('texto branco sobre cor escura e escuro sobre cor clara', () => {
  assert.equal(readableTextOn('#0F766E'), '#FFFFFF');
  assert.equal(readableTextOn('#FDE68A'), '#111827');
  assert.equal(readableTextOn('#F59E0B'), '#111827');
});

test('texto escolhido sempre atinge pelo menos 4.5:1 nas cores padrão', () => {
  for (const bg of ['#0F766E', '#134E4A', '#1D4ED8', '#BE185D', '#FFFFFF', '#FACC15']) {
    assert.ok(contrastRatio(bg, readableTextOn(bg)) >= 4.5, bg);
  }
});

test('cor inválida cai no tema padrão', () => {
  const vars = themeVariables({ primary: 'vermelho', accent: '#123456' });
  assert.equal(vars['--color-primary'], '#0F766E');
  assert.equal(vars['--color-accent'], '#123456');
});
