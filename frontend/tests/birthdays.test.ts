import { test } from 'node:test';
import assert from 'node:assert/strict';
import { birthdayDayLabel, birthdayMessage, insertAt, rangeFor, seenKey } from '../src/birthdays.ts';

test('mensagem de aniversário troca nome, idade e clínica', () => {
  assert.equal(
    birthdayMessage('Feliz aniversário, {nome}! {idade} anos 🎉 — {clinica} / {Clínica}', { fullName: ' Maria Souza ', age: 31, clinicName: 'Fisio Ana' }),
    'Feliz aniversário, Maria! 31 anos 🎉 — Fisio Ana / Fisio Ana');
  assert.equal(birthdayMessage('Oi {NOME}', { fullName: 'João', age: 1, clinicName: 'X' }), 'Oi João');
});

test('insere emoji na posição do cursor', () => {
  assert.deepEqual(insertAt('Oi Ana!', '🎉', 6), { text: 'Oi Ana🎉!', cursor: 8 });
  assert.deepEqual(insertAt('Oi', ' 🎂'), { text: 'Oi 🎂', cursor: 5 });
  assert.deepEqual(insertAt('Oi XYZ', 'Ana', 3, 6), { text: 'Oi Ana', cursor: 6 });
});

test('rótulo do dia do aniversário', () => {
  assert.equal(birthdayDayLabel('2026-10-15', '2026-10-15'), 'hoje');
  assert.equal(birthdayDayLabel('2026-10-16', '2026-10-15'), 'amanhã');
  assert.equal(birthdayDayLabel('2026-10-14', '2026-10-15'), 'ontem');
  assert.equal(birthdayDayLabel('2027-01-02', '2026-12-30'), '02/01');
});

test('períodos dos filtros: hoje, próximos 7 dias e mês', () => {
  assert.deepEqual(rangeFor('hoje', '2026-10-15'), { from: '2026-10-15', to: '2026-10-15' });
  assert.deepEqual(rangeFor('semana', '2026-12-28'), { from: '2026-12-28', to: '2027-01-03' });
  assert.deepEqual(rangeFor('mes', '2028-02-10'), { from: '2028-02-01', to: '2028-02-29' });
  assert.deepEqual(rangeFor('mes', '2026-12-31'), { from: '2026-12-01', to: '2026-12-31' });
});

test('aviso uma vez por dia por pessoa', () => {
  assert.equal(seenKey('Ana@Fisio.com', '2026-10-15'), 'physioevo:aniversarios:ana@fisio.com:2026-10-15');
});
