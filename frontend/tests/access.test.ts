import { test } from 'node:test';
import assert from 'node:assert/strict';
import { accessNotice, accessSeenKey, dateBrFull, inDays } from '../src/access.ts';
import type { ClinicAccessView } from '../src/api.ts';

const base: ClinicAccessView = {
  status: 'TRIAL', effectiveStatus: 'TRIAL', accessUntil: '2026-10-22', daysLeft: 7, readOnly: false, warning: 'SOON', contactEmail: null
};

test('datas e prazos por extenso', () => {
  assert.equal(dateBrFull('2026-10-22'), '22/10/2026');
  assert.equal(inDays(0), 'hoje');
  assert.equal(inDays(1), 'amanhã');
  assert.equal(inDays(5), 'em 5 dias');
});

test('aviso de vencimento: 7, 3, 1 e 0 dias abrem a janela; os outros só a faixa', () => {
  const seven = accessNotice(base)!;
  assert.equal(seven.title, 'Seu período de teste termina em 7 dias');
  assert.equal(seven.modal, true);
  assert.match(seven.text, /22\/10\/2026/);
  assert.match(seven.text, /só para consulta, e seus dados continuam guardados\. /);
  assert.equal(accessNotice({ ...base, daysLeft: 5 })!.modal, false);
  assert.equal(accessNotice({ ...base, daysLeft: 0 })!.title, 'Seu período de teste termina hoje');
  assert.equal(accessNotice({ ...base, daysLeft: 1, status: 'ACTIVE' })!.title, 'Sua assinatura vence amanhã');
  assert.equal(accessNotice({ ...base, daysLeft: 3, status: 'FREE' })!.title, 'Seu acesso gratuito termina em 3 dias');
});

test('vencida e suspensa: só consulta, com os dados guardados', () => {
  const expired = accessNotice({ ...base, effectiveStatus: 'EXPIRED', daysLeft: -2, readOnly: true, warning: 'EXPIRED' })!;
  assert.equal(expired.title, 'O período de teste terminou');
  assert.equal(expired.level, 'danger');
  assert.match(expired.text, /dados continuam guardados/);
  const suspended = accessNotice({ ...base, status: 'SUSPENDED', effectiveStatus: 'SUSPENDED', readOnly: true, warning: 'SUSPENDED' })!;
  assert.equal(suspended.title, 'Acesso suspenso');
  assert.equal(accessNotice({ ...base, warning: 'NONE' }), null);
});

test('janela do aviso uma vez por dia por pessoa e situação', () => {
  assert.equal(accessSeenKey('Ana@X.com', '2026-10-15', 'SOON'), 'physioevo:acesso:ana@x.com:2026-10-15:SOON');
});
