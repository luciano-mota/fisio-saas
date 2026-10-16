package com.fisiosaas.finance;

import com.fisiosaas.identity.CurrentClinic;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Relatório de fluxo de caixa da clínica num período: entradas (pagamentos de pacientes, com taxa da maquininha) e
 * saídas (mensalidades da PhysioEvo pagas), em ordem de data, com os totais. A página imprime com a logo da clínica.
 */
@RestController
public class CashFlowController {
    static final int MAX_DAYS = 366;

    /** kind: IN (entrada) ou OUT (saída). gross/fee/net já com o sinal da entrada (saídas: gross = net = valor pago). */
    public record Entry(LocalDate date, String kind, String description, String party, String method, BigDecimal gross,
                       BigDecimal fee, BigDecimal net) {}

    public record MethodTotal(String method, int count, BigDecimal gross, BigDecimal net) {}

    public record Totals(BigDecimal gross, BigDecimal fees, BigDecimal expenses, BigDecimal net, int entries, int exits) {}

    public record Header(String name, String professionalName, String crefito, String address, String whatsapp, Long logoVersion) {}

    public record CashFlow(Header clinic, LocalDate from, LocalDate to, List<Entry> entries, Totals totals,
                           List<MethodTotal> byMethod) {}

    private final JdbcTemplate jdbc;
    private final CurrentClinic current;

    public CashFlowController(JdbcTemplate jdbc, CurrentClinic current) {
        this.jdbc = jdbc;
        this.current = current;
    }

    @GetMapping("/api/v1/finance/cashflow")
    public CashFlow cashFlow(Authentication auth,
                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        UUID clinicId = current.require(auth).clinicId();
        validate(from, to);
        Header header = jdbc.queryForObject("""
                SELECT c.display_name, c.professional_name, c.crefito, c.address, c.whatsapp,
                       (SELECT (extract(epoch FROM l.updated_at) * 1000)::bigint FROM clinic_logos l WHERE l.clinic_id = c.id) AS logo_version
                  FROM clinics c WHERE c.id = ?""",
            (rs, n) -> new Header(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                (Long) rs.getObject(6)), clinicId);

        List<Entry> entries = new ArrayList<>(jdbc.query("""
                SELECT y.paid_on, y.method, y.charged_amount, y.fee_amount, y.net_amount, p.full_name,
                       CASE WHEN pp.id IS NOT NULL THEN 'Pacote de ' || ps.name || ' (' || pp.name || ')'
                            WHEN a.id IS NOT NULL THEN 'Sessão de ' || s.name
                            ELSE 'Atendimento' END AS description
                  FROM payments y
                  JOIN patients p ON p.id = y.patient_id AND p.clinic_id = y.clinic_id
                  LEFT JOIN patient_packages pp ON pp.id = y.patient_package_id AND pp.clinic_id = y.clinic_id
                  LEFT JOIN services ps ON ps.id = pp.service_id AND ps.clinic_id = pp.clinic_id
                  LEFT JOIN appointments a ON a.id = y.appointment_id AND a.clinic_id = y.clinic_id
                  LEFT JOIN services s ON s.id = a.service_id AND s.clinic_id = a.clinic_id
                 WHERE y.clinic_id = ? AND NOT y.canceled AND y.paid_on BETWEEN ? AND ?""",
            (rs, n) -> new Entry(rs.getObject("paid_on", LocalDate.class), "IN", rs.getString("description"),
                rs.getString("full_name"), rs.getString("method"), rs.getBigDecimal("charged_amount"),
                rs.getBigDecimal("fee_amount"), rs.getBigDecimal("net_amount")),
            clinicId, from, to));
        entries.addAll(jdbc.query("""
                SELECT paid_on, description, method, paid_amount FROM subscription_charges
                 WHERE clinic_id = ? AND status = 'PAID' AND paid_on BETWEEN ? AND ?""",
            (rs, n) -> new Entry(rs.getObject("paid_on", LocalDate.class), "OUT", "Assinatura PhysioEvo: " + rs.getString("description"),
                "PhysioEvo", rs.getString("method"), rs.getBigDecimal("paid_amount"), BigDecimal.ZERO, rs.getBigDecimal("paid_amount")),
            clinicId, from, to));
        return build(header, from, to, entries);
    }

    public static void validate(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) throw new IllegalArgumentException("A data final deve ser depois da inicial");
        if (ChronoUnit.DAYS.between(from, to) > MAX_DAYS) throw new IllegalArgumentException("Escolha um período de até um ano");
    }

    /** Ordena por data (entradas antes das saídas no mesmo dia) e soma os totais. */
    public static CashFlow build(Header header, LocalDate from, LocalDate to, List<Entry> entries) {
        entries.sort(Comparator.comparing(Entry::date).thenComparing(Entry::kind));
        BigDecimal gross = BigDecimal.ZERO, fees = BigDecimal.ZERO, expenses = BigDecimal.ZERO;
        int in = 0, out = 0;
        Map<String, MethodTotal> byMethod = new LinkedHashMap<>();
        for (Entry e : entries) {
            if ("IN".equals(e.kind())) {
                gross = gross.add(e.gross());
                fees = fees.add(e.fee());
                in++;
                byMethod.merge(e.method(), new MethodTotal(e.method(), 1, e.gross(), e.net()),
                    (a, b) -> new MethodTotal(a.method(), a.count() + 1, a.gross().add(b.gross()), a.net().add(b.net())));
            } else {
                expenses = expenses.add(e.net());
                out++;
            }
        }
        BigDecimal net = gross.subtract(fees).subtract(expenses);
        return new CashFlow(header, from, to, entries, new Totals(gross, fees, expenses, net, in, out),
            new ArrayList<>(byMethod.values()));
    }
}
