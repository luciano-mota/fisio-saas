package com.fisiosaas.finance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fisiosaas.finance.CardFees.Calculation;
import com.fisiosaas.finance.CardFees.Machine;
import com.fisiosaas.finance.CardFees.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CardFeesTest {
    static final LocalDate DAY = LocalDate.of(2026, 10, 15);
    static final Machine MACHINE = new Machine(new BigDecimal("1.99"), new BigDecimal("3.49"),
        Map.of(3, new BigDecimal("4.99")), BigDecimal.ZERO, 1, 30, false);

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    @Test
    void creditoParceladoSemRepasseDescontaATaxaEGeraUmRecebivelPorParcela() {
        Calculation c = CardFees.calculate(money("600"), Method.CREDITO, 3, MACHINE, false, DAY);
        assertThat(c.feePercent()).isEqualByComparingTo("4.99");
        assertThat(c.chargedAmount()).isEqualByComparingTo("600.00");
        assertThat(c.feeAmount()).isEqualByComparingTo("29.94");
        assertThat(c.netAmount()).isEqualByComparingTo("570.06");
        assertThat(c.receivables()).hasSize(3);
        assertThat(c.receivables().get(0).dueOn()).isEqualTo(DAY.plusDays(30));
        assertThat(c.receivables().get(2).dueOn()).isEqualTo(DAY.plusDays(90));
        // Centavos que sobram da divisão vão na 1ª parcela; a soma bate com o líquido.
        assertThat(c.receivables().stream().map(CardFees.Receivable::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
            .isEqualByComparingTo("570.06");
        assertThat(c.receivables().get(0).amount()).isEqualByComparingTo("190.02");
    }

    @Test
    void comRepasseCobraValorDivididoPorUmMenosTaxaEAClinicaRecebeOValorCheio() {
        Calculation c = CardFees.calculate(money("600"), Method.CREDITO, 3, MACHINE, true, DAY);
        assertThat(c.chargedAmount()).isEqualByComparingTo("631.51"); // 600 × 1,0499 daria 629,94 e faltaria dinheiro
        assertThat(c.netAmount()).isEqualByComparingTo("600.00");
        assertThat(c.feeAmount()).isEqualByComparingTo("31.51");
    }

    @Test
    void debitoAntecipadoEDinheiro() {
        Calculation debit = CardFees.calculate(money("90"), Method.DEBITO, 1, MACHINE, false, DAY);
        assertThat(debit.feeAmount()).isEqualByComparingTo("1.79");
        assertThat(debit.receivables()).singleElement().satisfies(r -> assertThat(r.dueOn()).isEqualTo(DAY.plusDays(1)));

        Machine anticipated = new Machine(money("1.99"), money("3.49"), Map.of(), money("0.50"), 1, 1, true);
        Calculation credit = CardFees.calculate(money("300"), Method.CREDITO, 6, anticipated, false, DAY);
        assertThat(credit.feePercent()).isEqualByComparingTo("3.49"); // sem taxa para 6x: usa a do crédito
        assertThat(credit.feeAmount()).isEqualByComparingTo("10.97"); // 10,47 + 0,50 fixo
        assertThat(credit.receivables()).singleElement().satisfies(r -> assertThat(r.dueOn()).isEqualTo(DAY.plusDays(1)));

        Calculation cash = CardFees.calculate(money("90"), Method.PIX, 1, null, true, DAY);
        assertThat(cash.netAmount()).isEqualByComparingTo("90.00");
        assertThat(cash.receivables()).isEmpty();
    }
}
