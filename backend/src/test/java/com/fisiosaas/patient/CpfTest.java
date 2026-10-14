package com.fisiosaas.patient;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CpfTest {

    @Test
    void aceitaCpfComDigitosVerificadoresCorretos() {
        assertThat(Cpf.isValid("52998224725")).isTrue();
        assertThat(Cpf.isValid("11144477735")).isTrue();
        // Primeiro dígito verificador com resto 10 vira 0.
        assertThat(Cpf.isValid("12345678909")).isTrue();
    }

    @Test
    void recusaDigitoErradoTamanhoErradoESequenciaRepetida() {
        assertThat(Cpf.isValid("52998224724")).isFalse();
        assertThat(Cpf.isValid("52998224715")).isFalse();
        assertThat(Cpf.isValid("5299822472")).isFalse();
        assertThat(Cpf.isValid("00000000000")).isFalse();
        assertThat(Cpf.isValid("99999999999")).isFalse();
        assertThat(Cpf.isValid("529.982.247-25")).isFalse();
        assertThat(Cpf.isValid(null)).isFalse();
    }
}
