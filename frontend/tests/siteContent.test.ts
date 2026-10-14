import { test } from 'node:test';
import assert from 'node:assert/strict';
import { bookingMessage, educationItems, mapEmbedUrl, mapLinkUrl, photoUrl } from '../src/siteContent.ts';
import { photoTarget } from '../src/photoSize.ts';

test('formação vira itens por linha ou ponto e vírgula, sem marcadores', () => {
  assert.deepEqual(educationItems('Fisioterapia – UFMA\n- Pilates clínico; • RPG\n\n'),
    ['Fisioterapia – UFMA', 'Pilates clínico', 'RPG']);
  assert.deepEqual(educationItems(null), []);
  assert.deepEqual(educationItems('  '), []);
});

test('mapa e link usam o endereço codificado', () => {
  const address = 'Rua das Palmeiras, 120 - Centro, São Luís';
  assert.equal(mapEmbedUrl(address),
    'https://www.google.com/maps?q=Rua%20das%20Palmeiras%2C%20120%20-%20Centro%2C%20S%C3%A3o%20Lu%C3%ADs&output=embed');
  assert.ok(mapLinkUrl(address).startsWith('https://www.google.com/maps/search/?api=1&query=Rua%20das'));
});

test('mensagem do WhatsApp cita o serviço quando há', () => {
  assert.equal(bookingMessage('Fisio Ana'), 'Olá! Vi o site da Fisio Ana e gostaria de agendar uma avaliação.');
  assert.equal(bookingMessage('Fisio Ana', 'Pilates'),
    'Olá! Vi o site da Fisio Ana e gostaria de agendar Pilates. Quais horários você tem?');
  assert.equal(photoUrl('fisio-ana', 'abc'), '/api/v1/public/clinics/fisio-ana/photos/abc');
});

test('foto é reduzida até o lado máximo e recusada se pequena ou comprida demais', () => {
  assert.deepEqual(photoTarget(4000, 3000, 1600), { width: 1600, height: 1200 });
  assert.deepEqual(photoTarget(1000, 800, 1600), { width: 1000, height: 800 });
  assert.deepEqual(photoTarget(3000, 4000, 1280), { width: 960, height: 1280 });
  assert.throws(() => photoTarget(300, 900, 1600), /pequena demais/);
  assert.throws(() => photoTarget(4000, 900, 1600), /comprida demais/);
  assert.throws(() => photoTarget(0, 0, 1600), /Não foi possível ler/);
});
