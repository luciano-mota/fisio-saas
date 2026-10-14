import { test } from 'node:test';
import assert from 'node:assert/strict';
import { confirmationMessage, firstName, renewalMessage, sessionsLabel } from '../src/messages.ts';

test('primeiro nome e plural de sessões', () => {
  assert.equal(firstName('  Maria Souza Lima '), 'Maria');
  assert.equal(sessionsLabel(1), '1 sessão');
  assert.equal(sessionsLabel(2), '2 sessões');
  assert.equal(sessionsLabel(0), 'nenhuma sessão');
});

test('mensagem de renovação conforme o saldo', () => {
  const pkg = { name: '10 sessões', serviceName: 'Pilates' };
  assert.equal(renewalMessage('Maria Souza', { ...pkg, remaining: 2 }),
    'Olá, Maria! Passando para avisar que restam 2 sessões do seu pacote de Pilates (10 sessões). ' +
    'Quer renovar para não interromper o tratamento?');
  assert.match(renewalMessage('Maria', { ...pkg, remaining: 1 }), /que resta 1 sessão do seu pacote/);
  assert.match(renewalMessage('Maria', { ...pkg, remaining: 0 }), /as sessões do seu pacote de Pilates \(10 sessões\) terminaram\./);
});

test('pedido de confirmação com hoje, amanhã ou dia da semana', () => {
  assert.equal(confirmationMessage('Maria Souza', 'Pilates', '2026-10-15T08:00:00', '2026-10-14'),
    'Olá, Maria! Confirmando sua sessão de Pilates amanhã (qui, 15/10) às 8h. Responda SIM para confirmar.');
  assert.equal(confirmationMessage('Maria', 'RPG', '2026-10-14T14:30:00', '2026-10-14'),
    'Olá, Maria! Confirmando sua sessão de RPG hoje (qua, 14/10) às 14h30. Responda SIM para confirmar.');
  assert.match(confirmationMessage('Maria', 'RPG', '2026-10-17T09:00:00', '2026-10-14'), /sessão de RPG no sáb, 17\/10 às 9h\./);
  assert.match(confirmationMessage('Maria', 'RPG', '2026-10-19T09:00:00', '2026-10-14'), /sessão de RPG na seg, 19\/10 às 9h\./);
});
