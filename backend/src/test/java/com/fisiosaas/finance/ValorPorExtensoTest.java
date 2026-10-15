package com.fisiosaas.finance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ValorPorExtensoTest {
    private static String r(String v) {
        return ValorPorExtenso.reais(new BigDecimal(v));
    }

    @Test
    void valoresDoDiaADia() {
        assertThat(r("90")).isEqualTo("noventa reais");
        assertThat(r("1")).isEqualTo("um real");
        assertThat(r("0.50")).isEqualTo("cinquenta centavos");
        assertThat(r("0.01")).isEqualTo("um centavo");
        assertThat(r("100")).isEqualTo("cem reais");
        assertThat(r("120.00")).isEqualTo("cento e vinte reais");
        assertThat(r("320.50")).isEqualTo("trezentos e vinte reais e cinquenta centavos");
        assertThat(r("631.51")).isEqualTo("seiscentos e trinta e um reais e cinquenta e um centavos");
        assertThat(r("815")).isEqualTo("oitocentos e quinze reais");
    }

    @Test
    void milhares() {
        assertThat(r("1000")).isEqualTo("mil reais");
        assertThat(r("1100")).isEqualTo("mil e cem reais");
        assertThat(r("1030")).isEqualTo("mil e trinta reais");
        assertThat(r("1234.56")).isEqualTo("mil duzentos e trinta e quatro reais e cinquenta e seis centavos");
        assertThat(r("2000")).isEqualTo("dois mil reais");
        assertThat(r("12800")).isEqualTo("doze mil e oitocentos reais");
        assertThat(r("21500.10")).isEqualTo("vinte e um mil e quinhentos reais e dez centavos");
    }

    @Test
    void milhoes() {
        assertThat(r("1000000")).isEqualTo("um milhão de reais");
        assertThat(r("2500000")).isEqualTo("dois milhões e quinhentos mil reais");
        assertThat(r("1000001")).isEqualTo("um milhão e um reais");
    }
}
