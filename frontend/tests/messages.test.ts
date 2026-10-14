import { test } from 'node:test';
import assert from 'node:assert/strict';
import { firstName, renewalMessage, sessionsLabel } from '../src/messages.ts';

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
