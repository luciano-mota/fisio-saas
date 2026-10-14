/**
 * Prepara a logo no navegador: aceita PNG, JPG, WebP ou SVG, redimensiona (máx. 512 px) e converte para PNG,
 * que é o que o servidor aceita. Também devolve uma amostra pequena dos pixels para sugerir as cores.
 */

export const MAX_SIDE = 512;
export const ACCEPT = 'image/png,image/jpeg,image/webp,image/svg+xml';
const MAX_BYTES = 1024 * 1024;

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error('Não foi possível ler essa imagem. Tente PNG ou JPG.'));
    img.src = src;
  });
}

function toBlob(canvas: HTMLCanvasElement): Promise<Blob> {
  return new Promise((resolve, reject) =>
    canvas.toBlob(blob => (blob ? resolve(blob) : reject(new Error('Falha ao converter a imagem'))), 'image/png'));
}

/** Pixels RGBA da imagem reduzida a no máx. 64×64 (suficiente para achar as cores dominantes). */
export function samplePixels(img: CanvasImageSource, width: number, height: number): Uint8ClampedArray {
  const scale = Math.min(1, 64 / Math.max(width, height));
  const canvas = document.createElement('canvas');
  canvas.width = Math.max(1, Math.round(width * scale));
  canvas.height = Math.max(1, Math.round(height * scale));
  const ctx = canvas.getContext('2d', { willReadFrequently: true })!;
  ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
  return ctx.getImageData(0, 0, canvas.width, canvas.height).data;
}

export async function prepareLogo(file: File): Promise<{ png: Blob; pixels: Uint8ClampedArray }> {
  if (!ACCEPT.split(',').includes(file.type)) throw new Error('Use uma imagem PNG, JPG, WebP ou SVG.');
  if (file.size > 5 * 1024 * 1024) throw new Error('Imagem muito grande (máximo 5 MB).');
  const url = URL.createObjectURL(file);
  try {
    const img = await loadImage(url);
    // SVG sem tamanho declarado chega com 0×0: desenha em 512.
    const naturalW = img.naturalWidth || MAX_SIDE;
    const naturalH = img.naturalHeight || MAX_SIDE;
    for (const side of [MAX_SIDE, 384, 256]) {
      const scale = Math.min(1, side / Math.max(naturalW, naturalH));
      const canvas = document.createElement('canvas');
      canvas.width = Math.max(16, Math.round(naturalW * scale));
      canvas.height = Math.max(16, Math.round(naturalH * scale));
      canvas.getContext('2d')!.drawImage(img, 0, 0, canvas.width, canvas.height);
      const png = await toBlob(canvas);
      if (png.size <= MAX_BYTES) return { png, pixels: samplePixels(canvas, canvas.width, canvas.height) };
    }
    throw new Error('Não foi possível deixar a logo com menos de 1 MB.');
  } finally {
    URL.revokeObjectURL(url);
  }
}

/** Amostra de pixels de uma logo já salva (para sugerir cores de novo sem reenviar). */
export async function pixelsFromUrl(url: string): Promise<Uint8ClampedArray> {
  const img = await loadImage(url);
  return samplePixels(img, img.naturalWidth, img.naturalHeight);
}
