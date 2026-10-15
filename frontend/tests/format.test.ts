import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ageOn, formatCpf, formatPhone, isValidCpf, formatDocument, maskCpf, maskPhone } from '../src/format.ts';

test('formata CPF e telefone guardados só com dígitos', () => {
  assert.equal(formatCpf('52998224725'), '529.982.247-25');
  assert.equal(formatCpf(null), '');
  assert.equal(formatPhone('98999991234'), '(98) 99999-1234');
  assert.equal(formatPhone('9832221234'), '(98) 3222-1234');
  assert.equal(formatPhone('5598999991234'), '(98) 99999-1234');
});

test('valida CPF pelos dígitos verificadores, como o backend', () => {
  assert.ok(isValidCpf('529.982.247-25'));
  assert.ok(isValidCpf('12345678909'));
  assert.ok(!isValidCpf('529.982.247-24'));
  assert.ok(!isValidCpf('111.111.111-11'));
  assert.ok(!isValidCpf('1234'));
});

test('idade só aumenta no dia do aniversário', () => {
  assert.equal(ageOn('1980-05-17', new Date(2026, 4, 16)), 45);
  assert.equal(ageOn('1980-05-17', new Date(2026, 4, 17)), 46);
  assert.equal(ageOn('1980-05-17', new Date(2026, 9, 14)), 46);
});

test('CPF ou CNPJ do recibo', () => {
  assert.equal(formatDocument('52998224725'), '529.982.247-25');
  assert.equal(formatDocument('11222333000181'), '11.222.333/0001-81');
  assert.equal(formatDocument('11.222.333/0001-81'), '11.222.333/0001-81');
  assert.equal(formatDocument(null), '');
});

test('máscaras enquanto digita', () => {
  assert.equal(maskCpf('123'), '123');
  assert.equal(maskCpf('1234567'), '123.456.7');
  assert.equal(maskCpf('529.982.247-25999'), '529.982.247-25');
  assert.equal(maskPhone(''), '');
  assert.equal(maskPhone('9'), '(9');
  assert.equal(maskPhone('9898'), '(98) 98');
  assert.equal(maskPhone('9832221234'), '(98) 3222-1234');
  assert.equal(maskPhone('98981112233'), '(98) 98111-2233');
  assert.equal(maskPhone('+55 98 98111-2233'), '(98) 98111-2233');
});
