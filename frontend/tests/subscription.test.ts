import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { SubscriptionCharge } from '../src/api.ts';
import { adminStatusLabel, chargeNotice, chargeSeenKey, clinicStatusLabel } from '../src/subscription.ts';

const charge = (patch: Partial<SubscriptionCharge>): SubscriptionCharge => ({
  id: 'c1', clinicId: 'k', clinicName: 'Fisio Ana', referenceMonth: '2026-10-01', description: 'Mensalidade de outubro/2026',
  amount: 79.9, dueOn: '2026-10-22', status: 'OPEN', overdue: false, informedAt: null, informedBy: null, paidOn: null,
  paidAmount: null, method: null, confirmedBy: null, notes: null, ...patch
});

test('situação: despesa para a clínica, receita para a plataforma', () => {
  assert.equal(clinicStatusLabel(charge({})), 'A pagar');
  assert.equal(clinicStatusLabel(charge({ overdue: true })), 'Atrasada');
  assert.equal(clinicStatusLabel(charge({ status: 'INFORMED' })), 'Aguardando confirmação');
  assert.equal(clinicStatusLabel(charge({ status: 'PAID' })), 'Paga');
  assert.equal(adminStatusLabel(charge({ status: 'INFORMED' })), 'Pagamento informado');
  assert.equal(adminStatusLabel(charge({ status: 'PAID' })), 'Recebida');
  assert.equal(adminStatusLabel(charge({})), 'Em aberto');
});

test('aviso da mensalidade: 7, 3, 1 e 0 dias abrem a janela; atrasada sempre', () => {
  const seven = chargeNotice([charge({})], '2026-10-15')!;
  assert.equal(seven.title, 'Mensalidade de outubro/2026 vence em 7 dias');
  assert.equal(seven.modal, true);
  assert.match(seven.text, /22\/10\/2026/);
  assert.equal(chargeNotice([charge({})], '2026-10-17')!.modal, false); // 5 dias: só a faixa
  assert.equal(chargeNotice([charge({})], '2026-10-22')!.title, 'Mensalidade de outubro/2026 vence hoje');
  assert.equal(chargeNotice([charge({})], '2026-10-21')!.title, 'Mensalidade de outubro/2026 vence amanhã');
  const late = chargeNotice([charge({ overdue: true })], '2026-10-25')!;
  assert.equal(late.level, 'danger');
  assert.equal(late.modal, true);
  assert.equal(chargeNotice([charge({})], '2026-10-01'), null); // longe do vencimento
});

test('pagamento informado: só a faixa de aguardando confirmação', () => {
  const n = chargeNotice([charge({ status: 'INFORMED' })], '2026-10-15')!;
  assert.equal(n.title, 'Pagamento informado');
  assert.equal(n.modal, false);
  assert.equal(chargeNotice([charge({ status: 'PAID' })], '2026-10-15'), null);
  assert.equal(chargeSeenKey('Ana@X.com', 'c1', '2026-10-15'), 'physioevo:mensalidade:ana@x.com:c1:2026-10-15');
});
