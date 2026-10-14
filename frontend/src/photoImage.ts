/**
 * Prepara uma foto do site no navegador: aceita JPG, PNG, WebP (e HEIC onde o navegador abrir), reduz para no
 * máximo 1600 px de lado e recomprime em JPEG até caber em 800 KB. Redesenhar no canvas também descarta os
 * metadados da câmera (EXIF com localização); o servidor confere de novo.
 */
import { photoTarget } from './photoSize.ts';

export const ACCEPT = 'image/jpeg,image/png,image/webp,image/heic,image/heif';
const MAX_BYTES = 800 * 1024;

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error('Não foi possível ler essa foto. Use JPG ou PNG.'));
    img.src = src;
  });
}

function toJpeg(canvas: HTMLCanvasElement, quality: number): Promise<Blob> {
  return new Promise((resolve, reject) =>
    canvas.toBlob(blob => (blob ? resolve(blob) : reject(new Error('Falha ao converter a foto'))), 'image/jpeg', quality));
}

export async function preparePhoto(file: File): Promise<Blob> {
  if (!ACCEPT.split(',').includes(file.type)) throw new Error('Use uma foto JPG, PNG ou WebP.');
  if (file.size > 25 * 1024 * 1024) throw new Error('Foto muito grande (máximo 25 MB).');
  const url = URL.createObjectURL(file);
  try {
    const img = await loadImage(url);
    for (const [side, quality] of [[1600, 0.85], [1600, 0.75], [1280, 0.75], [1024, 0.7]] as const) {
      const { width, height } = photoTarget(img.naturalWidth, img.naturalHeight, side);
      const canvas = document.createElement('canvas');
      canvas.width = width;
      canvas.height = height;
      const ctx = canvas.getContext('2d')!;
      ctx.fillStyle = '#ffffff'; // PNG transparente vira fundo branco no JPEG
      ctx.fillRect(0, 0, width, height);
      ctx.drawImage(img, 0, 0, width, height);
      const jpeg = await toJpeg(canvas, quality);
      if (jpeg.size <= MAX_BYTES) return jpeg;
    }
    throw new Error('Não foi possível deixar a foto com menos de 800 KB.');
  } finally {
    URL.revokeObjectURL(url);
  }
}
