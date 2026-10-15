import { test } from 'node:test';
import assert from 'node:assert/strict';
import { acceptedMessage, declinedMessage, formatHour, hoursSummary, isoWeekdayOf, nextDays, requestSentMessage,
  whenLabel } from '../src/booking.ts';

const h = (weekday: number, start: string, end: string) => ({ weekday, start, end });

test('hora curta', () => {
  assert.equal(formatHour('08:00'), '8h');
  assert.equal(formatHour('13:30'), '13h30');
});

test('horário de atendimento junta dias seguidos iguais', () => {
  const week = [1, 2, 3, 4, 5].flatMap(d => [h(d, '14:00', '18:00'), h(d, '08:00', '12:00')]);
  assert.deepEqual(hoursSummary([...week, h(6, '08:00', '12:00')]),
    ['Seg a Sex: 8h às 12h e 14h às 18h', 'Sáb: 8h às 12h']);
  assert.deepEqual(hoursSummary([h(1, '07:00', '11:30'), h(3, '07:00', '11:30'), h(4, '07:00', '11:30')]),
    ['Seg: 7h às 11h30', 'Qua e Qui: 7h às 11h30']);
  assert.deepEqual(hoursSummary([]), []);
});

test('datas e mensagens do agendamento', () => {
  assert.deepEqual(nextDays('2026-10-30', 3), ['2026-10-30', '2026-10-31', '2026-11-01']);
  assert.equal(isoWeekdayOf('2026-10-18'), 7);
  assert.equal(isoWeekdayOf('2026-10-19'), 1);
  assert.equal(whenLabel('2026-10-16T10:00'), 'sex, 16/10 às 10:00');
  assert.equal(requestSentMessage('Fisio Ana', 'Maria Souza', 'Pilates', '2026-10-16T10:00'),
    'Olá! Sou Maria e acabei de pedir pelo site da Fisio Ana um horário de Pilates para sex, 16/10 às 10:00. Fico aguardando a confirmação.');
  assert.equal(acceptedMessage('Maria Souza', 'Pilates', '2026-10-16T10:00:00'),
    'Olá, Maria! Seu horário de Pilates está confirmado para sex, 16/10 às 10:00. Qualquer imprevisto, é só avisar por aqui.');
  assert.match(declinedMessage('Maria', 'Pilates', '2026-10-16T10:00:00', 'agenda cheia'), /\(agenda cheia\)\. Podemos combinar outro horário\?$/);
});
