package com.fisiosaas.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Valor em reais por extenso, para o recibo ("trezentos e vinte reais e cinquenta centavos"). */
public final class ValorPorExtenso {
    private ValorPorExtenso() {}

    private static final String[] UNIDADES = {"zero", "um", "dois", "três", "quatro", "cinco", "seis", "sete", "oito", "nove",
        "dez", "onze", "doze", "treze", "quatorze", "quinze", "dezesseis", "dezessete", "dezoito", "dezenove"};
    private static final String[] DEZENAS = {"", "", "vinte", "trinta", "quarenta", "cinquenta", "sessenta", "setenta",
        "oitenta", "noventa"};
    private static final String[] CENTENAS = {"", "cento", "duzentos", "trezentos", "quatrocentos", "quinhentos", "seiscentos",
        "setecentos", "oitocentos", "novecentos"};

    public static String reais(BigDecimal value) {
        BigDecimal v = value.setScale(2, RoundingMode.HALF_UP);
        if (v.signum() < 0 || v.compareTo(new BigDecimal("999999999.99")) > 0) throw new IllegalArgumentException("Valor fora do limite");
        long inteiro = v.longValue();
        int centavos = v.remainder(BigDecimal.ONE).movePointRight(2).intValue();
        StringBuilder text = new StringBuilder();
        if (inteiro > 0) {
            text.append(numero(inteiro));
            // "um milhão de reais", "dois milhões de reais"; "mil reais" sem "de".
            boolean exatoMilhao = inteiro >= 1_000_000 && inteiro % 1_000_000 == 0;
            text.append(exatoMilhao ? " de " : " ").append(inteiro == 1 ? "real" : "reais");
        }
        if (centavos > 0) {
            if (inteiro > 0) text.append(" e ");
            text.append(numero(centavos)).append(centavos == 1 ? " centavo" : " centavos");
        }
        return inteiro == 0 && centavos == 0 ? "zero real" : text.toString();
    }

    static String numero(long n) {
        if (n == 0) return UNIDADES[0];
        long milhoes = n / 1_000_000;
        long milhares = (n / 1000) % 1000;
        int resto = (int) (n % 1000);
        StringBuilder out = new StringBuilder();
        if (milhoes > 0) out.append(ate999((int) milhoes)).append(milhoes == 1 ? " milhão" : " milhões");
        if (milhares > 0) {
            if (out.length() > 0) out.append(resto == 0 && (milhares < 100 || milhares % 100 == 0) ? " e " : ", ");
            out.append(milhares == 1 ? "mil" : ate999((int) milhares) + " mil");
        }
        if (resto > 0) {
            // "mil e cem", "dois mil e trinta"; mas "mil, cento e dez" e "mil duzentos e trinta e quatro".
            if (out.length() > 0) out.append(resto < 100 || resto % 100 == 0 ? " e " : " ");
            out.append(ate999(resto));
        }
        return out.toString();
    }

    private static String ate999(int n) {
        if (n == 100) return "cem";
        int c = n / 100;
        int d = n % 100;
        StringBuilder out = new StringBuilder(CENTENAS[c]);
        if (d > 0) {
            if (c > 0) out.append(" e ");
            if (d < 20) out.append(UNIDADES[d]);
            else {
                out.append(DEZENAS[d / 10]);
                if (d % 10 > 0) out.append(" e ").append(UNIDADES[d % 10]);
            }
        }
        return out.toString();
    }
}
