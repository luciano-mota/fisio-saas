import { test } from 'node:test';
import assert from 'node:assert/strict';
import { autoHeadline, autoSubtitle, classify, fontsUrl, joinNames, resolveFonts, resolveStyle, serviceIcon } from '../src/siteStyle.ts';

const s = (name: string, description?: string) => ({ name, description });

test('Pilates e estética puxam para o estilo sereno, com os motivos', () => {
  const c = classify([s('Pilates'), s('Drenagem linfática'), s('RPG')]);
  assert.equal(c.style, 'sereno');
  assert.deepEqual(c.reasons, ['Pilates', 'Drenagem linfática']);
});

test('esportiva/ortopedia = dinâmico; infantil/domiciliar = acolhedor; respiratória = clínico', () => {
  assert.equal(classify([s('Fisioterapia esportiva'), s('Ortopedia e traumatologia')]).style, 'dinamico');
  assert.equal(classify([s('Atendimento domiciliar'), s('Fisioterapia para idosos')]).style, 'acolhedor');
  assert.equal(classify([s('Fisioterapia respiratória'), s('Neurofuncional')]).style, 'clinico');
});

test('descrição e texto "sobre" ajudam, mas o nome do serviço pesa mais', () => {
  assert.equal(classify([s('Sessão', 'Pilates de solo e aparelhos')]).style, 'sereno');
  assert.equal(classify([s('Fisioterapia esportiva'), s('Avaliação', 'pilates')]).style, 'dinamico');
  assert.equal(classify([s('Sessão')], 'Atendo crianças e bebês com carinho').style, 'acolhedor');
});

test('sem pistas fica clínico; escolha da clínica vale sobre o automático', () => {
  assert.equal(classify([s('Sessão de fisioterapia')]).style, 'clinico');
  assert.equal(classify([]).style, 'clinico');
  assert.equal(resolveStyle('auto', [s('Pilates')]), 'sereno');
  assert.equal(resolveStyle('dinamico', [s('Pilates')]), 'dinamico');
  assert.equal(resolveStyle('<script>', [s('Pilates')]), 'sereno');
  assert.equal(resolveFonts('auto', 'sereno'), 'elegante');
  assert.equal(resolveFonts('moderna', 'sereno'), 'moderna');
  assert.equal(resolveFonts('Comic Sans', 'acolhedor'), 'suave');
});

test('ícone de cada serviço', () => {
  assert.equal(serviceIcon('Pilates clínico'), 'pilates');
  assert.equal(serviceIcon('RPG'), 'spine');
  assert.equal(serviceIcon('Drenagem linfática'), 'hands');
  assert.equal(serviceIcon('Atendimento domiciliar'), 'home');
  assert.equal(serviceIcon('Fisioterapia respiratória'), 'lungs');
  assert.equal(serviceIcon('Fisio pediátrica'), 'child');
  assert.equal(serviceIcon('Avaliação', 'pós-AVC'), 'brain');
  assert.equal(serviceIcon('Sessão'), 'activity');
});

test('textos automáticos', () => {
  assert.equal(joinNames(['Pilates', 'RPG', 'Drenagem']), 'Pilates, RPG e Drenagem');
  assert.equal(joinNames(['Pilates']), 'Pilates');
  assert.equal(autoHeadline('acolhedor', [s('Atendimento domiciliar')]), 'Fisioterapia de qualidade, no conforto da sua casa');
  assert.equal(autoSubtitle([s('Pilates'), s('RPG')]), 'Pilates e RPG com atendimento individual e acompanhamento de perto.');
});

test('URL do Google Fonts só com fontes da lista', () => {
  assert.equal(fontsUrl('elegante'),
    'https://fonts.googleapis.com/css2?family=Cormorant+Garamond:wght@500;600;700&family=Lato:wght@400;700&display=swap');
});
