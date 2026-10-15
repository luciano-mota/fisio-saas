package com.fisiosaas.pix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fisiosaas.pix.PixCode.KeyType;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PixCodeTest {

    @Test
    void crcBateComOExemploDoManualDoBancoCentral() {
        String semCrc = "00020126580014br.gov.bcb.pix0136123e4567-e12b-12d1-a456-426655440000"
            + "5204000053039865802BR5913Fulano de Tal6008BRASILIA62070503***6304";
        assertThat(PixCode.crc16(semCrc)).isEqualTo("1D3D");
    }

    @Test
    void codigoComValorEIdentificadorDoPedido() {
        String code = PixCode.build("+5598999990000", "Clínica Movimento & Saúde Fisioterapia", "São Luís",
            new BigDecimal("90"), "FISIO-AB12CD");
        assertThat(code).startsWith("000201" + "26360014br.gov.bcb.pix0114+5598999990000" + "52040000" + "5303986" + "540590.00"
            + "5802BR" + "5925CLINICA MOVIMENTO SAUDE F" + "6008SAO LUIS" + "62150511FISIOAB12CD" + "6304");
        assertThat(code.substring(code.length() - 4)).isEqualTo(PixCode.crc16(code.substring(0, code.length() - 4)));
    }

    @Test
    void semValorEOPagadorDigita() {
        String code = PixCode.build("fulano@email.com", "Ana", "Recife", null, null);
        assertThat(code).doesNotContain("5405").contains("62070503***6304");
    }

    @Test
    void normalizaEValidaAsChaves() {
        assertThat(PixCode.normalizeKey(KeyType.TELEFONE, "(98) 99999-0000")).isEqualTo("+5598999990000");
        assertThat(PixCode.normalizeKey(KeyType.CPF, "529.982.247-25")).isEqualTo("52998224725");
        assertThat(PixCode.normalizeKey(KeyType.CNPJ, "11.222.333/0001-81")).isEqualTo("11222333000181");
        assertThat(PixCode.normalizeKey(KeyType.EMAIL, " Ana@Email.com ")).isEqualTo("ana@email.com");
        assertThat(PixCode.normalizeKey(KeyType.ALEATORIA, "123E4567-E89B-12D3-A456-426614174000"))
            .isEqualTo("123e4567-e89b-12d3-a456-426614174000");
        assertThatThrownBy(() -> PixCode.normalizeKey(KeyType.CPF, "111.111.111-11")).hasMessageContaining("CPF inválido");
        assertThatThrownBy(() -> PixCode.normalizeKey(KeyType.TELEFONE, "9999")).hasMessageContaining("telefone");
    }

    @Test
    void qrCodeEmSvg() {
        String svg = PixCode.qrSvg(PixCode.build("+5598999990000", "Ana", "Recife", new BigDecimal("10"), "X1"));
        assertThat(svg).startsWith("<svg").contains("viewBox").contains("<path");
    }
}
