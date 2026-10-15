import { test } from 'node:test';
import assert from 'node:assert/strict';
import { addMonths, brl, chargeMessage, dateBr, methodLabel, monthLabel, monthRange, moneyInput, parseMoney,
  sessionPaymentState } from '../src/finance.ts';

test('valor digitado em reais', () => {
  assert.equal(parseMoney('1.234,56'), 1234.56);
  assert.equal(parseMoney('90'), 90);
  assert.equal(parseMoney('90,5'), 90.5);
  assert.equal(parseMoney('90.50'), 90.5);
  assert.equal(parseMoney('R$ 1.200'), 1200);
  assert.equal(parseMoney('  '), null);
  assert.ok(Number.isNaN(parseMoney('abc')));
  assert.ok(Number.isNaN(parseMoney('10,999')));
});

test('formatação de valores', () => {
  assert.equal(brl(1234.5), 'R$ 1.234,50');
  assert.equal(moneyInput(90), '90,00');
  assert.equal(moneyInput(null), '');
});

test('forma de pagamento', () => {
  assert.equal(methodLabel('CREDITO', 3, 'Stone'), 'Crédito 3x · Stone');
  assert.equal(methodLabel('DEBITO', 1, 'Ton'), 'Débito · Ton');
  assert.equal(methodLabel('PIX'), 'Pix');
});

test('meses', () => {
  assert.equal(monthLabel('2026-10'), 'outubro de 2026');
  assert.equal(addMonths('2026-01', -1), '2025-12');
  assert.equal(addMonths('2026-12', 1), '2027-01');
  assert.deepEqual(monthRange('2028-02'), ['2028-02-01', '2028-02-29']);
  assert.equal(dateBr('2026-10-15'), '15/10/2026');
});

test('lembrete de valor em aberto', () => {
  assert.equal(chargeMessage('Maria Souza', 300, 'Clínica Mova'),
    'Olá, Maria! Tudo bem? Aqui é da Clínica Mova. Passando para lembrar que ficou em aberto R$ 300,00 dos seus atendimentos. ' +
    'Pode ser por Pix, dinheiro ou cartão. Se já pagou, me avise para eu dar baixa. Obrigado!');
});

test('situação da sessão avulsa', () => {
  assert.equal(sessionPaymentState(90, 0), 'a pagar');
  assert.equal(sessionPaymentState(90, 40), 'pago em parte');
  assert.equal(sessionPaymentState(90, 90), 'pago');
  assert.equal(sessionPaymentState(null, 0), null);
  assert.equal(sessionPaymentState(0, 0), null);
});
