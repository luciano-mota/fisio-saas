import { test } from 'node:test';
import assert from 'node:assert/strict';
import { alertTitle, latestSeen, newSince, requestLine, tabTitle, type RecentRequest } from '../src/requestAlerts.ts';

const r = (id: string, createdAt: number): RecentRequest =>
  ({ id, fullName: 'Maria Souza', serviceName: 'Pilates', startsAt: '2026-10-16T08:00:00', createdAt });

test('primeiro carregamento não avisa; depois só os pedidos novos, do mais antigo ao mais novo', () => {
  const recent = [r('b', 200), r('a', 100)];
  assert.deepEqual(newSince(recent, null), []);
  const seen = latestSeen(recent, null);
  assert.equal(seen, 200);
  assert.deepEqual(newSince([r('c', 300), r('d', 250), ...recent], seen).map(x => x.id), ['d', 'c']);
  assert.deepEqual(newSince(recent, seen), []);
  // Pedido respondido sai da lista: o "visto" não volta para trás.
  assert.equal(latestSeen([], 300), 300);
});

test('textos do aviso e título da aba', () => {
  assert.equal(requestLine(r('a', 1)), 'Maria · Pilates · sex, 16/10 às 08:00');
  assert.equal(alertTitle(1), 'Novo pedido de agendamento');
  assert.equal(alertTitle(3), '3 novos pedidos de agendamento');
  assert.equal(tabTitle('Fisio Ana', 2), '(2) Fisio Ana · PhysioEvo');
  assert.equal(tabTitle('Fisio Ana', 0), 'Fisio Ana · PhysioEvo');
});
