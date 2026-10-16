package com.fisiosaas.finance;

import com.fisiosaas.finance.PaymentController.PaymentView;
import com.fisiosaas.identity.CurrentClinic;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Financeiro: quem está devendo, resumo do mês, a receber da maquininha e a conta de cada paciente.
 * Cobranças = pacotes vendidos (valor combinado) + sessões avulsas realizadas (valor da sessão). Em aberto =
 * cobranças − pagamentos não estornados.
 */
@RestController
@RequestMapping("/api/v1")
public class FinanceController {

    public record Debtor(UUID patientId, String fullName, String whatsapp, BigDecimal charged, BigDecimal paid,
                         BigDecimal balance, LocalDate lastPaymentOn) {}

    /**
     * expenses: mensalidades da PhysioEvo pagas no mês (despesa confirmada pela plataforma); expensesPending: pagamento
     * informado pela clínica e ainda não confirmado. result: o que sobra no mês = líquido das taxas − despesas.
     */
    public record Summary(String month, BigDecimal received, BigDecimal fees, BigDecimal net, BigDecimal toReceive,
                          BigDecimal open, int payments, List<MethodTotal> byMethod, BigDecimal expenses,
                          BigDecimal expensesPending, BigDecimal result) {}

    public record MethodTotal(String method, BigDecimal charged, BigDecimal fees, BigDecimal net) {}

    public record ReceivableView(UUID id, UUID paymentId, String patientName, String machineName, int installment,
                                 int installments, LocalDate dueOn, BigDecimal amount, LocalDate receivedOn) {}

    /** Item cobrado do paciente: pacote vendido ou sessão avulsa realizada, com quanto já foi pago dele. */
    public record Charge(String kind, UUID id, String description, LocalDate date, BigDecimal value, BigDecimal paid) {}

    public record PatientFinance(BigDecimal charged, BigDecimal paid, BigDecimal balance, List<Charge> charges,
                                 List<PaymentView> payments) {}

    public record PriceRequest(@DecimalMin("0") @Digits(integer = 8, fraction = 2) BigDecimal price) {}

    /** Cobranças por paciente (pacotes + avulsas realizadas). */
    static final String CHARGES = """
        SELECT patient_id, price AS value FROM patient_packages WHERE clinic_id = ? AND price IS NOT NULL
        UNION ALL
        SELECT patient_id, price FROM appointments
         WHERE clinic_id = ? AND kind = 'SESSION' AND status = 'DONE' AND patient_package_id IS NULL AND price IS NOT NULL
        """;

    private final JdbcTemplate jdbc;
    private final CurrentClinic current;
    private final PaymentController payments;

    public FinanceController(JdbcTemplate jdbc, CurrentClinic current, PaymentController payments) {
        this.jdbc = jdbc;
        this.current = current;
        this.payments = payments;
    }

    /** Pacientes com valor em aberto, do maior para o menor. */
    @GetMapping("/finance/debtors")
    public List<Debtor> debtors(Authentication auth) {
        UUID clinicId = current.require(auth).clinicId();
        return jdbc.query("""
                WITH charged AS (SELECT patient_id, sum(value) AS total FROM (%s) c GROUP BY patient_id),
                     paid AS (SELECT patient_id, sum(amount) AS total, max(paid_on) AS last_on FROM payments
                               WHERE clinic_id = ? AND NOT canceled GROUP BY patient_id)
                SELECT p.id, p.full_name, p.whatsapp, coalesce(c.total, 0) AS charged, coalesce(y.total, 0) AS paid,
                       coalesce(c.total, 0) - coalesce(y.total, 0) AS balance, y.last_on
                  FROM patients p
                  LEFT JOIN charged c ON c.patient_id = p.id
                  LEFT JOIN paid y ON y.patient_id = p.id
                 WHERE p.clinic_id = ? AND coalesce(c.total, 0) - coalesce(y.total, 0) > 0
                 ORDER BY balance DESC, p.full_name""".formatted(CHARGES),
            (rs, n) -> new Debtor(rs.getObject("id", UUID.class), rs.getString("full_name"), rs.getString("whatsapp"),
                rs.getBigDecimal("charged"), rs.getBigDecimal("paid"), rs.getBigDecimal("balance"),
                rs.getObject("last_on", LocalDate.class)),
            clinicId, clinicId, clinicId, clinicId);
    }

    /** Resumo do mês ("2026-10"): recebido (bruto), taxas, líquido; a receber da maquininha e total em aberto hoje. */
    @GetMapping("/finance/summary")
    public Summary summary(Authentication auth, @RequestParam String month) {
        UUID clinicId = current.require(auth).clinicId();
        YearMonth ym;
        try {
            ym = YearMonth.parse(month);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Mês inválido");
        }
        LocalDate from = ym.atDay(1);
        LocalDate to = ym.atEndOfMonth();
        List<MethodTotal> byMethod = jdbc.query("""
                SELECT method, sum(charged_amount), sum(fee_amount), sum(net_amount) FROM payments
                 WHERE clinic_id = ? AND NOT canceled AND paid_on BETWEEN ? AND ? GROUP BY method ORDER BY method""",
            (rs, n) -> new MethodTotal(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4)),
            clinicId, from, to);
        BigDecimal received = byMethod.stream().map(MethodTotal::charged).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fees = byMethod.stream().map(MethodTotal::fees).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal net = byMethod.stream().map(MethodTotal::net).reduce(BigDecimal.ZERO, BigDecimal::add);
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM payments WHERE clinic_id = ? AND NOT canceled AND paid_on BETWEEN ? AND ?",
            Integer.class, clinicId, from, to);
        BigDecimal toReceive = jdbc.queryForObject("""
                SELECT coalesce(sum(r.amount), 0) FROM payment_receivables r
                  JOIN payments y ON y.id = r.payment_id AND y.clinic_id = r.clinic_id
                 WHERE r.clinic_id = ? AND r.received_on IS NULL AND NOT y.canceled""", BigDecimal.class, clinicId);
        BigDecimal open = debtors(auth).stream().map(Debtor::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expenses = jdbc.queryForObject("""
                SELECT coalesce(sum(paid_amount), 0) FROM subscription_charges
                 WHERE clinic_id = ? AND status = 'PAID' AND paid_on BETWEEN ? AND ?""", BigDecimal.class, clinicId, from, to);
        BigDecimal expensesPending = jdbc.queryForObject("""
                SELECT coalesce(sum(amount), 0) FROM subscription_charges WHERE clinic_id = ? AND status = 'INFORMED'""",
            BigDecimal.class, clinicId);
        return new Summary(ym.toString(), received, fees, net, toReceive, open, count == null ? 0 : count, byMethod,
            expenses, expensesPending, net.subtract(expenses));
    }

    @GetMapping("/finance/receivables")
    public List<ReceivableView> receivables(Authentication auth, @RequestParam(defaultValue = "false") boolean includeReceived) {
        UUID clinicId = current.require(auth).clinicId();
        return jdbc.query("""
                SELECT r.*, p.full_name, m.name AS machine_name, y.installments AS total FROM payment_receivables r
                  JOIN payments y ON y.id = r.payment_id AND y.clinic_id = r.clinic_id
                  JOIN patients p ON p.id = y.patient_id AND p.clinic_id = y.clinic_id
                  LEFT JOIN card_machines m ON m.id = y.machine_id AND m.clinic_id = y.clinic_id
                 WHERE r.clinic_id = ? AND NOT y.canceled AND (? OR r.received_on IS NULL)
                 ORDER BY r.received_on NULLS FIRST, r.due_on LIMIT 300""",
            (rs, n) -> new ReceivableView(rs.getObject("id", UUID.class), rs.getObject("payment_id", UUID.class),
                rs.getString("full_name"), rs.getString("machine_name"), rs.getInt("installment"), rs.getInt("total"),
                rs.getObject("due_on", LocalDate.class), rs.getBigDecimal("amount"), rs.getObject("received_on", LocalDate.class)),
            clinicId, includeReceived);
    }

    /** Conferido no extrato: marca como recebido (ou desmarca). */
    @PostMapping("/finance/receivables/{id}/received")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void received(Authentication auth, @PathVariable UUID id, @RequestParam(defaultValue = "true") boolean received) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        int updated = jdbc.update("UPDATE payment_receivables SET received_on = CASE WHEN ? THEN ? END WHERE id = ? AND clinic_id = ?",
            received, payments.today(clinicId), id, clinicId);
        if (updated == 0) throw new NoSuchElementException();
    }

    /** Conta do paciente: o que foi cobrado (pacotes e avulsas), o que pagou e o saldo. */
    @GetMapping("/patients/{patientId}/finance")
    public PatientFinance patient(Authentication auth, @PathVariable UUID patientId) {
        UUID clinicId = current.require(auth).clinicId();
        Integer found = jdbc.queryForObject("SELECT count(*) FROM patients WHERE id = ? AND clinic_id = ?", Integer.class, patientId, clinicId);
        if (found == null || found == 0) throw new NoSuchElementException();
        List<Charge> charges = jdbc.query("""
                SELECT 'PACOTE' AS kind, pp.id, s.name || ' · ' || pp.name AS description, pp.purchased_on AS date, pp.price AS value,
                       coalesce((SELECT sum(amount) FROM payments y WHERE y.patient_package_id = pp.id AND y.clinic_id = pp.clinic_id
                                  AND NOT y.canceled), 0) AS paid
                  FROM patient_packages pp JOIN services s ON s.id = pp.service_id AND s.clinic_id = pp.clinic_id
                 WHERE pp.clinic_id = ? AND pp.patient_id = ? AND pp.price IS NOT NULL
                UNION ALL
                SELECT 'SESSAO', a.id, s.name || ' (avulsa)', (a.starts_at AT TIME ZONE c.timezone)::date, a.price,
                       coalesce((SELECT sum(amount) FROM payments y WHERE y.appointment_id = a.id AND y.clinic_id = a.clinic_id
                                  AND NOT y.canceled), 0)
                  FROM appointments a JOIN services s ON s.id = a.service_id AND s.clinic_id = a.clinic_id
                  JOIN clinics c ON c.id = a.clinic_id
                 WHERE a.clinic_id = ? AND a.patient_id = ? AND a.kind = 'SESSION' AND a.status = 'DONE'
                   AND a.patient_package_id IS NULL AND a.price IS NOT NULL
                 ORDER BY date DESC""",
            (rs, n) -> new Charge(rs.getString("kind"), rs.getObject("id", UUID.class), rs.getString("description"),
                rs.getObject("date", LocalDate.class), rs.getBigDecimal("value"), rs.getBigDecimal("paid")),
            clinicId, patientId, clinicId, patientId);
        List<PaymentView> list = jdbc.query(PaymentController.SELECT
            + " WHERE y.clinic_id = ? AND y.patient_id = ? ORDER BY y.paid_on DESC, y.created_at DESC",
            PaymentController.MAPPER, clinicId, patientId);
        BigDecimal charged = charges.stream().map(Charge::value).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal paid = list.stream().filter(p -> !p.canceled()).map(PaymentView::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PatientFinance(charged, paid, charged.subtract(paid), charges, list);
    }

    /** Valor de uma sessão avulsa (desconto, cortesia = 0). */
    @PutMapping("/appointments/{id}/price")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void price(Authentication auth, @PathVariable UUID id, @Valid @RequestBody PriceRequest body) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        int updated = jdbc.update("UPDATE appointments SET price = ?, updated_at = now() WHERE id = ? AND clinic_id = ? AND kind = 'SESSION'",
            body.price(), id, clinicId);
        if (updated == 0) throw new NoSuchElementException();
    }
}
