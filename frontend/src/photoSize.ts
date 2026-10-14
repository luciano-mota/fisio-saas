/** Tamanho final de uma foto do site (puro, testável): o servidor aceita lados entre 320 e 2400 px. */
export const MIN_SIDE = 320;
const MAX_RATIO = 4;

export function photoTarget(width: number, height: number, maxSide: number): { width: number; height: number } {
  if (!width || !height) throw new Error('Não foi possível ler essa foto. Use JPG ou PNG.');
  if (Math.min(width, height) < MIN_SIDE) {
    throw new Error(`Foto pequena demais: use uma com pelo menos ${MIN_SIDE} px de altura e largura.`);
  }
  if (Math.max(width, height) / Math.min(width, height) > MAX_RATIO) {
    throw new Error('Foto comprida demais. Recorte para um formato mais quadrado.');
  }
  const scale = Math.min(1, maxSide / Math.max(width, height));
  return { width: Math.round(width * scale), height: Math.round(height * scale) };
}
