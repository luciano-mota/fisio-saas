import { test } from 'node:test';
import assert from 'node:assert/strict';
import { isoWeekday, seriesDates } from '../src/recurrence.ts';

test('seg/qua/sex por 5 semanas dá 15 datas (mesmo resultado do backend)', () => {
  const dates = seriesDates('2030-01-07', [1, 3, 5], 5);
  assert.equal(dates.length, 15);
  assert.equal(dates[0], '2030-01-07');
  assert.equal(dates[14], '2030-02-08');
});

test('semanas contam a partir da data inicial', () => {
  assert.deepEqual(seriesDates('2030-01-09', [1, 3, 5], 1), ['2030-01-09', '2030-01-11', '2030-01-14']);
});

test('quantidade de sessões e limite', () => {
  assert.equal(seriesDates('2030-01-07', [1, 3, 5], undefined, 10).at(-1), '2030-01-28');
  assert.equal(seriesDates('2030-01-07', [1, 3, 5], 2, 10).length, 6);
  assert.equal(seriesDates('2030-01-07', [1, 2, 3, 4, 5, 6, 7], 52).length, 101);
  assert.deepEqual(seriesDates('2030-01-07', [], 5), []);
});

test('dia da semana ISO', () => {
  assert.equal(isoWeekday('2030-01-07'), 1);
  assert.equal(isoWeekday('2030-01-13'), 7);
});
