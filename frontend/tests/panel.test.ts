import { test } from 'node:test';
import assert from 'node:assert/strict';
import { dayChip, daySummary, firstName, greeting, longDayLabel, primaryAction, sessionsCount, summaryText } from '../src/panel.ts';
import { avatarInitials } from '../src/format.ts';

test('saudação conforme a hora', () => {
  assert.equal(greeting(7), 'Bom dia');
  assert.equal(greeting(13), 'Boa tarde');
  assert.equal(greeting(20), 'Boa noite');
  assert.equal(greeting(2), 'Boa noite');
});

test('primeiro nome sem título e iniciais do avatar', () => {
  assert.equal(firstName('Dra. Ana Ribeiro'), 'Ana');
  assert.equal(firstName('Ft. João'), 'João');
  assert.equal(firstName(null), '');
  assert.equal(avatarInitials('Maria Souza Lima'), 'ML');
  assert.equal(avatarInitials('joão'), 'J');
  assert.equal(avatarInitials('  '), '?');
});

test('datas por extenso e faixa da semana', () => {
  assert.equal(longDayLabel('2026-10-14'), 'quarta-feira, 14 de outubro');
  assert.equal(longDayLabel('2026-01-04'), 'domingo, 4 de janeiro');
  assert.deepEqual(dayChip('2026-10-05'), { day: 'seg', number: '5' });
});

test('resumo do dia ignora bloqueios e canceladas', () => {
  const s = daySummary([
    { kind: 'SESSION', status: 'SCHEDULED' }, { kind: 'SESSION', status: 'CONFIRMED' },
    { kind: 'SESSION', status: 'DONE' }, { kind: 'SESSION', status: 'CANCELED' }, { kind: 'BLOCK', status: 'SCHEDULED' }
  ]);
  assert.deepEqual(s, { sessions: 3, toConfirm: 1, pending: 0, done: 1 });
  assert.equal(summaryText(s), '3 sessões · 1 a confirmar · 1 realizada');
  assert.equal(summaryText(daySummary([])), 'Nenhuma sessão');
});

test('sessão que já passou não conta como "a confirmar"', () => {
  const s = daySummary([
    { kind: 'SESSION', status: 'SCHEDULED', startsAt: '2026-10-15T08:00:00' },
    { kind: 'SESSION', status: 'CONFIRMED', startsAt: '2026-10-15T09:00:00' },
    { kind: 'SESSION', status: 'SCHEDULED', startsAt: '2026-10-15T16:00:00' }
  ], '2026-10-15T10:30');
  assert.deepEqual(s, { sessions: 3, toConfirm: 1, pending: 2, done: 0 });
  assert.equal(summaryText(s), '3 sessões · 1 a confirmar · 2 sem registro');
  assert.equal(sessionsCount(1), '1 sessão');
  assert.equal(sessionsCount(0), 'nenhuma sessão');
});

test('ação em destaque depende do horário, do status e do WhatsApp', () => {
  const scheduled = { kind: 'SESSION', status: 'SCHEDULED' } as const;
  const confirmed = { kind: 'SESSION', status: 'CONFIRMED' } as const;
  assert.equal(primaryAction(scheduled, false, true), 'ask-confirmation');
  assert.equal(primaryAction(scheduled, false, false), 'confirm');
  assert.equal(primaryAction(confirmed, false, true), null);
  assert.equal(primaryAction(confirmed, true, true), 'done');
  assert.equal(primaryAction({ kind: 'SESSION', status: 'DONE' }, true, true), null);
  assert.equal(primaryAction({ kind: 'BLOCK', status: 'SCHEDULED' }, true, true), null);
});
