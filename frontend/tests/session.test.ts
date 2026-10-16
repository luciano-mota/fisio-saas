import { test } from 'node:test';
import assert from 'node:assert/strict';
import { meFailure, wakeDelay } from '../src/session.ts';

test('sessão encerrada só com 401 ou 403', () => {
  assert.equal(meFailure(401), 'logged-out');
  assert.equal(meFailure(403), 'logged-out');
});

test('servidor acordando ou sem rede não derruba o login', () => {
  assert.equal(meFailure(502), 'unavailable');
  assert.equal(meFailure(503), 'unavailable');
  assert.equal(meFailure(504), 'unavailable');
  assert.equal(meFailure(500), 'unavailable');
  assert.equal(meFailure(undefined), 'unavailable');
});

test('espera crescente entre as tentativas', () => {
  assert.deepEqual([0, 1, 2, 9].map(wakeDelay), [3000, 5000, 8000, 8000]);
});
