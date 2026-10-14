package com.fisiosaas.clinic;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/** Conferências de imagens enviadas pela clínica (logo e fotos do site). Nunca confia no Content-Type do cliente. */
final class Images {
    private Images() {}

    static boolean isPng(byte[] data) {
        return data.length > 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G';
    }

    static boolean isJpeg(byte[] data) {
        return data.length > 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF;
    }

    /**
     * Lê só o cabeçalho da imagem (sem decodificar os pixels) para recusar imagens enormes ou corrompidas.
     * {@code noun} entra nas mensagens: "logo" → "A logo pode ter no máximo..." / "Logo pequena demais".
     */
    static int[] dimensions(byte[] data, int minSide, int maxSide, String noun) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("Imagem inválida");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width > maxSide || height > maxSide) {
                    throw new IllegalArgumentException("A " + noun + " pode ter no máximo " + maxSide + " px de lado");
                }
                if (width < minSide || height < minSide) {
                    throw new IllegalArgumentException(
                        Character.toUpperCase(noun.charAt(0)) + noun.substring(1) + " pequena demais");
                }
                return new int[] {width, height};
            } finally {
                reader.dispose();
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("Imagem inválida");
        }
    }

    /**
     * Tira do JPEG os segmentos de metadados (EXIF com GPS e modelo da câmera, XMP, comentários), que não podem
     * ir para o site público. Mantém só o necessário para exibir: JFIF (APP0), perfil de cor (APP2), Adobe (APP14)
     * e os segmentos de imagem. Percorre os marcadores sem decodificar os pixels.
     */
    static byte[] stripJpegMetadata(byte[] data) {
        if (!isJpeg(data)) throw new IllegalArgumentException("Imagem inválida");
        var out = new ByteArrayOutputStream(data.length);
        out.write(0xFF);
        out.write(0xD8);
        int i = 2;
        while (i + 4 <= data.length) {
            if ((data[i] & 0xFF) != 0xFF) throw new IllegalArgumentException("Imagem inválida");
            int marker = data[i + 1] & 0xFF;
            if (marker == 0xFF) { // preenchimento entre marcadores
                i++;
                continue;
            }
            if (marker == 0xDA) { // início dos dados da imagem: daqui até o fim é copiado como está
                out.write(data, i, data.length - i);
                return out.toByteArray();
            }
            int length = ((data[i + 2] & 0xFF) << 8) | (data[i + 3] & 0xFF);
            if (length < 2 || i + 2 + length > data.length) throw new IllegalArgumentException("Imagem inválida");
            boolean metadata = (marker >= 0xE1 && marker <= 0xEF && marker != 0xE2 && marker != 0xEE) || marker == 0xFE;
            if (!metadata) out.write(data, i, 2 + length);
            i += 2 + length;
        }
        throw new IllegalArgumentException("Imagem inválida");
    }
}
