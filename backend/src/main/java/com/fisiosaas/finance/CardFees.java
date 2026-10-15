package com.fisiosaas.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cálculo da taxa da maquininha (função pura). Sem repasse: o paciente paga o valor e a clínica recebe o valor menos a
 * taxa. Com repasse: cobra-se valor ÷ (1 − taxa) para a clínica receber o valor cheio (multiplicar por (1 + taxa)
 * daria menos). Também gera os recebíveis: débito e crédito à vista/antecipado numa data só; parcelado, um por parcela.
 */
public final class CardFees {
    private CardFees() {}

    static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public enum Method { DINHEIRO, PIX, DEBITO, CREDITO }

    /** Taxas da maquininha: % débito, % crédito à vista, % por nº de parcelas, taxa fixa (R$) e prazos. */
    public record Machine(BigDecimal debitFee, BigDecimal creditFee, Map<Integer, BigDecimal> installmentFees,
                          BigDecimal fixedFee, int debitDays, int creditDays, boolean anticipated) {}

    public record Receivable(int installment, LocalDate dueOn, BigDecimal amount) {}

    public record Calculation(BigDecimal feePercent, BigDecimal chargedAmount, BigDecimal feeAmount, BigDecimal netAmount,
                              List<Receivable> receivables) {}

    public static Calculation calculate(BigDecimal amount, Method method, int installments, Machine machine,
                                        boolean passFee, LocalDate paidOn) {
        BigDecimal value = amount.setScale(2, RoundingMode.HALF_UP);
        if (method == Method.DINHEIRO || method == Method.PIX) {
            return new Calculation(BigDecimal.ZERO.setScale(2), value, BigDecimal.ZERO.setScale(2), value, List.of());
        }
        BigDecimal percent = method == Method.DEBITO ? machine.debitFee()
            : installments <= 1 ? machine.creditFee()
            : machine.installmentFees().getOrDefault(installments, machine.creditFee());
        BigDecimal rate = percent.divide(HUNDRED, 10, RoundingMode.HALF_UP);
        BigDecimal fixed = machine.fixedFee().setScale(2, RoundingMode.HALF_UP);

        BigDecimal charged;
        BigDecimal fee;
        if (passFee) {
            charged = value.add(fixed).divide(BigDecimal.ONE.subtract(rate), 2, RoundingMode.HALF_UP);
            fee = charged.subtract(value);
        } else {
            charged = value;
            fee = value.multiply(rate).add(fixed).setScale(2, RoundingMode.HALF_UP).min(value);
        }
        BigDecimal net = charged.subtract(fee);

        List<Receivable> receivables = new ArrayList<>();
        if (method == Method.DEBITO) {
            receivables.add(new Receivable(1, paidOn.plusDays(machine.debitDays()), net));
        } else if (installments <= 1 || machine.anticipated()) {
            receivables.add(new Receivable(1, paidOn.plusDays(machine.creditDays()), net));
        } else {
            BigDecimal part = net.divide(BigDecimal.valueOf(installments), 2, RoundingMode.DOWN);
            BigDecimal first = net.subtract(part.multiply(BigDecimal.valueOf(installments - 1)));
            for (int i = 1; i <= installments; i++) {
                receivables.add(new Receivable(i, paidOn.plusDays((long) machine.creditDays() * i), i == 1 ? first : part));
            }
        }
        return new Calculation(percent.setScale(2, RoundingMode.HALF_UP), charged, fee, net, receivables);
    }
}
