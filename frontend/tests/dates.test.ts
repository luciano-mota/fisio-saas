import { test } from 'node:test';
import assert from 'node:assert/strict';
import { addDays, dayLabel, minutesBetween, nowLocal, timeOf, today, weekStart } from '../src/dates.ts';

test('soma dias atravessando mês e ano', () => {
  assert.equal(addDays('2026-10-31', 1), '2026-11-01');
  assert.equal(addDays('2026-12-31', 1), '2027-01-01');
  assert.equal(addDays('2026-03-01', -1), '2026-02-28');
});

test('semana começa na segunda, inclusive quando a data é domingo', () => {
  assert.equal(weekStart('2026-10-14'), '2026-10-12'); // quarta
  assert.equal(weekStart('2026-10-12'), '2026-10-12'); // segunda
  assert.equal(weekStart('2026-10-18'), '2026-10-12'); // domingo
});

test('rótulos de dia e hora em português', () => {
  assert.equal(dayLabel('2026-10-15'), 'qui, 15/10');
  assert.equal(dayLabel('2026-10-18T08:00:00'), 'dom, 18/10');
  assert.equal(timeOf('2026-10-15T08:05:00'), '08:05');
});

test('duração em minutos, inclusive passando da meia-noite', () => {
  assert.equal(minutesBetween('2026-10-15T08:00:00', '2026-10-15T08:50:00'), 50);
  assert.equal(minutesBetween('2026-10-15T23:30:00', '2026-10-16T00:30:00'), 60);
});

test('hoje e agora vêm do relógio local do aparelho', () => {
  const d = new Date(2026, 0, 5, 7, 9);
  assert.equal(today(d), '2026-01-05');
  assert.equal(nowLocal(d), '2026-01-05T07:09');
});
