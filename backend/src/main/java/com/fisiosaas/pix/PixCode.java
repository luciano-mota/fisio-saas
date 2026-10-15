package com.fisiosaas.pix;

import com.fisiosaas.patient.Cpf;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Map;

/**
 * Pix "copia e cola" estático (BR Code, padrão EMV do Banco Central), com valor e identificador do pedido. Sem API
 * de banco e sem taxa: o paciente paga direto na chave da clínica. O QR Code é o mesmo texto desenhado em SVG.
 */
public final class PixCode {
    private PixCode() {}

    public enum KeyType { CPF, CNPJ, TELEFONE, EMAIL, ALEATORIA }

    /** Chave no formato que o Pix espera (CPF/CNPJ só dígitos, telefone +55…, e-mail minúsculo, aleatória minúscula). */
    public static String normalizeKey(KeyType type, String raw) {
        String value = raw == null ? "" : raw.trim();
        switch (type) {
            case CPF -> {
                String d = value.replaceAll("\\D", "");
                if (!Cpf.isValid(d)) throw new IllegalArgumentException("Chave Pix: CPF inválido");
                return d;
            }
            case CNPJ -> {
                String d = value.replaceAll("\\D", "");
                if (!isValidCnpj(d)) throw new IllegalArgumentException("Chave Pix: CNPJ inválido");
                return d;
            }
            case TELEFONE -> {
                String d = value.replaceAll("\\D", "");
                if (d.length() == 10 || d.length() == 11) d = "55" + d;
                if (!d.matches("55\\d{10,11}")) throw new IllegalArgumentException("Chave Pix: telefone com DDD, ex.: (98) 99999-0000");
                return "+" + d;
            }
            case EMAIL -> {
                String e = value.toLowerCase();
                if (e.length() > 77 || !e.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) throw new IllegalArgumentException("Chave Pix: e-mail inválido");
                return e;
            }
            case ALEATORIA -> {
                String k = value.toLowerCase();
                if (!k.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
                    throw new IllegalArgumentException("Chave Pix aleatória inválida (formato 123e4567-e89b-12d3-a456-426614174000)");
                }
                return k;
            }
            default -> throw new IllegalArgumentException("Tipo de chave Pix inválido");
        }
    }

    /**
     * Monta o código. {@code amount} nulo = o pagador digita o valor. {@code txid}: até 25 letras/números (o que não
     * for letra ou número é removido; vazio vira "***", o padrão para Pix estático sem identificador).
     */
    public static String build(String key, String name, String city, BigDecimal amount, String txid) {
        StringBuilder account = new StringBuilder()
            .append(field("00", "br.gov.bcb.pix"))
            .append(field("01", key));
        String id = txid == null ? "" : txid.replaceAll("[^A-Za-z0-9]", "");
        if (id.length() > 25) id = id.substring(0, 25);
        StringBuilder payload = new StringBuilder()
            .append(field("00", "01"))
            .append(field("26", account.toString()))
            .append(field("52", "0000"))
            .append(field("53", "986"));
        if (amount != null) payload.append(field("54", amount.setScale(2, RoundingMode.HALF_UP).toPlainString()));
        payload.append(field("58", "BR"))
            .append(field("59", ascii(name, 25, "RECEBEDOR")))
            .append(field("60", ascii(city, 15, "BRASIL")))
            .append(field("62", field("05", id.isEmpty() ? "***" : id)))
            .append("6304");
        return payload + crc16(payload.toString());
    }

    /** QR Code do código em SVG (módulos escuros num único path; escala livre pelo CSS). */
    public static String qrSvg(String code) {
        try {
            BitMatrix m = new QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, 0, 0,
                Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 2,
                    EncodeHintType.CHARACTER_SET, "UTF-8"));
            StringBuilder path = new StringBuilder();
            for (int y = 0; y < m.getHeight(); y++) {
                for (int x = 0; x < m.getWidth(); x++) {
                    if (m.get(x, y)) path.append('M').append(x).append(' ').append(y).append("h1v1h-1z");
                }
            }
            return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" shape-rendering=\"crispEdges\">"
                .formatted(m.getWidth(), m.getHeight())
                + "<rect width=\"100%\" height=\"100%\" fill=\"#fff\"/><path fill=\"#000\" d=\"" + path + "\"/></svg>";
        } catch (WriterException e) {
            throw new IllegalStateException("Não foi possível gerar o QR Code", e);
        }
    }

    private static String field(String id, String value) {
        int length = value.getBytes(StandardCharsets.UTF_8).length;
        if (length > 99) throw new IllegalArgumentException("Campo do Pix muito longo");
        return id + String.format("%02d", length) + value;
    }

    /** Nome e cidade: sem acento, só letras, números e espaço, em maiúsculas, cortado no tamanho do padrão. */
    static String ascii(String text, int max, String fallback) {
        String plain = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "").replaceAll("[^A-Za-z0-9 ]", " ").replaceAll("\\s+", " ").trim().toUpperCase();
        if (plain.isEmpty()) plain = fallback;
        return plain.length() > max ? plain.substring(0, max).trim() : plain;
    }

    /** CRC16-CCITT (polinômio 0x1021, início 0xFFFF), em 4 dígitos hexadecimais maiúsculos. */
    static String crc16(String payload) {
        int crc = 0xFFFF;
        for (byte b : payload.getBytes(StandardCharsets.UTF_8)) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
            crc &= 0xFFFF;
        }
        return String.format("%04X", crc);
    }

    static boolean isValidCnpj(String d) {
        if (d == null || !d.matches("\\d{14}") || d.chars().distinct().count() == 1) return false;
        int[] w1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int[] w2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        return digit(d, w1) == d.charAt(12) - '0' && digit(d, w2) == d.charAt(13) - '0';
    }

    private static int digit(String d, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) sum += (d.charAt(i) - '0') * weights[i];
        int rest = sum % 11;
        return rest < 2 ? 0 : 11 - rest;
    }
}
