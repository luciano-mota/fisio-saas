package com.fisiosaas.patient;

/** Validação de CPF pelos dígitos verificadores (recibo para dedução no IR). */
public final class Cpf {
    private Cpf() {}

    /** Recebe só dígitos. Recusa tamanho errado, sequências repetidas (111.111.111-11) e dígito verificador errado. */
    public static boolean isValid(String digits) {
        if (digits == null || !digits.matches("\\d{11}") || digits.chars().distinct().count() == 1) return false;
        return checkDigit(digits, 9) == digits.charAt(9) - '0' && checkDigit(digits, 10) == digits.charAt(10) - '0';
    }

    private static int checkDigit(String digits, int length) {
        int sum = 0;
        for (int i = 0; i < length; i++) sum += (digits.charAt(i) - '0') * (length + 1 - i);
        int rest = (sum * 10) % 11;
        return rest == 10 ? 0 : rest;
    }
}
