package com.fisiosaas.finance;

import com.fisiosaas.finance.CardFees.Calculation;
import com.fisiosaas.finance.CardFees.Method;
import com.fisiosaas.finance.CardFees.Receivable;
import com.fisiosaas.identity.CurrentClinic;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Pagamentos dos pacientes. O servidor calcula taxa, líquido e recebíveis (o painel só mostra uma prévia).
 * Pagamento de pacote/sessão precisa ser do mesmo paciente (o banco também garante). Estornar = cancelar.
 */
@RestController
@RequestMapping("/api/v1")
public class PaymentController {

    public record PaymentRequest(
        @NotNull UUID patientId,
        UUID patientPackageId,
        UUID appointmentId,
        @NotNull Method method,
        UUID machineId,
        @Min(1) @Max(12) Integer installments,
        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 8, fraction = 2) BigDecimal amount,
        LocalDate paidOn,
        @Size(max = 300) String notes) {}

    /** Prévia do cálculo (mesmas regras do registro), para o painel mostrar taxa e líquido antes de salvar. */
    public record PreviewRequest(@NotNull Method method, UUID machineId, @Min(1) @Max(12) Integer installments,
                                 @NotNull @DecimalMin(value = "0.01") @Digits(integer = 8, fraction = 2) BigDecimal amount) {}

    public record PaymentView(UUID id, UUID patientId, String patientName, UUID patientPackageId, String packageName,
                              UUID appointmentId, LocalDate appointmentDate, String method, UUID machineId, String machineName,
                              int installments, BigDecimal amount, BigDecimal chargedAmount, BigDecimal feePercent,
                              BigDecimal feeAmount, BigDecimal netAmount, LocalDate paidOn, String notes, boolean canceled) {}

    static final String SELECT = """
        SELECT y.*, p.full_name AS patient_name, pp.name AS package_name, m.name AS machine_name,
               (a.starts_at AT TIME ZONE c.timezone)::date AS appointment_date
          FROM payments y
          JOIN clinics c ON c.id = y.clinic_id
          JOIN patients p ON p.id = y.patient_id AND p.clinic_id = y.clinic_id
          LEFT JOIN patient_packages pp ON pp.id = y.patient_package_id AND pp.clinic_id = y.clinic_id
          LEFT JOIN appointments a ON a.id = y.appointment_id AND a.clinic_id = y.clinic_id
          LEFT JOIN card_machines m ON m.id = y.machine_id AND m.clinic_id = y.clinic_id
        """;

    static final RowMapper<PaymentView> MAPPER = (rs, n) -> new PaymentView(rs.getObject("id", UUID.class),
        rs.getObject("patient_id", UUID.class), rs.getString("patient_name"), rs.getObject("patient_package_id", UUID.class),
        rs.getString("package_name"), rs.getObject("appointment_id", UUID.class), rs.getObject("appointment_date", LocalDate.class),
        rs.getString("method"), rs.getObject("machine_id", UUID.class), rs.getString("machine_name"), rs.getInt("installments"),
        rs.getBigDecimal("amount"), rs.getBigDecimal("charged_amount"), rs.getBigDecimal("fee_percent"),
        rs.getBigDecimal("fee_amount"), rs.getBigDecimal("net_amount"), rs.getObject("paid_on", LocalDate.class),
        rs.getString("notes"), rs.getBoolean("canceled"));

    private final JdbcTemplate jdbc;
    private final CurrentClinic current;
    private final CardMachineController machines;

    public PaymentController(JdbcTemplate jdbc, CurrentClinic current, CardMachineController machines) {
        this.jdbc = jdbc;
        this.current = current;
        this.machines = machines;
    }

    @GetMapping("/payments")
    public List<PaymentView> list(Authentication auth, @RequestParam LocalDate from, @RequestParam LocalDate to) {
        UUID clinicId = current.require(auth).clinicId();
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 400) throw new IllegalArgumentException("Período inválido");
        return jdbc.query(SELECT + " WHERE y.clinic_id = ? AND y.paid_on BETWEEN ? AND ? ORDER BY y.paid_on DESC, y.created_at DESC",
            MAPPER, clinicId, from, to);
    }

    @PostMapping("/payments/preview")
    public Calculation preview(Authentication auth, @Valid @RequestBody PreviewRequest body) {
        UUID clinicId = current.require(auth).clinicId();
        return calculate(clinicId, body.method(), body.machineId(), body.installments(), body.amount(), today(clinicId));
    }

    @PostMapping("/payments")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public PaymentView create(Authentication auth, @Valid @RequestBody PaymentRequest body) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        Integer patient = jdbc.queryForObject("SELECT count(*) FROM patients WHERE id = ? AND clinic_id = ?",
            Integer.class, body.patientId(), clinicId);
        if (patient == null || patient == 0) throw new NoSuchElementException();
        if (body.patientPackageId() != null && body.appointmentId() != null) {
            throw new IllegalArgumentException("Escolha o pacote ou a sessão, não os dois");
        }
        if (body.patientPackageId() != null) requireOwn("patient_packages", clinicId, body.patientId(), body.patientPackageId(), "Pacote");
        if (body.appointmentId() != null) requireOwn("appointments", clinicId, body.patientId(), body.appointmentId(), "Sessão");

        LocalDate paidOn = body.paidOn() == null ? today(clinicId) : body.paidOn();
        int installments = body.installments() == null ? 1 : body.installments();
        Calculation calc = calculate(clinicId, body.method(), body.machineId(), installments, body.amount(), paidOn);
        UUID id = UUID.randomUUID();
        boolean card = body.method() == Method.DEBITO || body.method() == Method.CREDITO;
        jdbc.update("""
            INSERT INTO payments(id, clinic_id, patient_id, patient_package_id, appointment_id, method, machine_id, installments,
                                 amount, charged_amount, fee_percent, fee_amount, net_amount, paid_on, notes)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            id, clinicId, body.patientId(), body.patientPackageId(), body.appointmentId(), body.method().name(),
            card ? body.machineId() : null, body.method() == Method.CREDITO ? installments : 1, body.amount(),
            calc.chargedAmount(), calc.feePercent(), calc.feeAmount(), calc.netAmount(), paidOn,
            body.notes() == null || body.notes().isBlank() ? null : body.notes().trim());
        for (Receivable r : calc.receivables()) {
            jdbc.update("INSERT INTO payment_receivables(id, clinic_id, payment_id, installment, due_on, amount) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), clinicId, id, r.installment(), r.dueOn(), r.amount());
        }
        return find(clinicId, id);
    }

    /** Estorno: o pagamento continua no histórico, mas deixa de abater a dívida e sai do "a receber". */
    @PostMapping("/payments/{id}/cancel")
    @Transactional
    public PaymentView cancel(Authentication auth, @PathVariable UUID id) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        PaymentView payment = find(clinicId, id);
        if (payment.canceled()) throw new IllegalArgumentException("Pagamento já estornado");
        jdbc.update("UPDATE payments SET canceled = TRUE, canceled_at = now() WHERE id = ? AND clinic_id = ?", id, clinicId);
        return find(clinicId, id);
    }

    /** Hoje no fuso da clínica. */
    LocalDate today(UUID clinicId) {
        String zone = jdbc.queryForObject("SELECT timezone FROM clinics WHERE id = ?", String.class, clinicId);
        return LocalDate.now(java.time.ZoneId.of(zone));
    }

    PaymentView find(UUID clinicId, UUID id) {
        return jdbc.query(SELECT + " WHERE y.id = ? AND y.clinic_id = ?", MAPPER, id, clinicId)
            .stream().findFirst().orElseThrow(NoSuchElementException::new);
    }

    private Calculation calculate(UUID clinicId, Method method, UUID machineId, Integer installments, BigDecimal amount,
                                  LocalDate paidOn) {
        boolean card = method == Method.DEBITO || method == Method.CREDITO;
        if (card && machineId == null) throw new IllegalArgumentException("Escolha a maquininha");
        int n = installments == null ? 1 : installments;
        if (n > 1 && method != Method.CREDITO) throw new IllegalArgumentException("Parcelamento só no crédito");
        Boolean pass = jdbc.queryForObject("SELECT pass_card_fee FROM clinics WHERE id = ?", Boolean.class, clinicId);
        return CardFees.calculate(amount, method, n, card ? machines.fees(clinicId, machineId) : null,
            Boolean.TRUE.equals(pass), paidOn);
    }

    /** Pacote/sessão precisa ser da clínica E do paciente informado. */
    private void requireOwn(String table, UUID clinicId, UUID patientId, UUID id, String what) {
        Integer found = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id = ? AND clinic_id = ? AND patient_id = ?",
            Integer.class, id, clinicId, patientId);
        if (found == null || found == 0) throw new IllegalArgumentException(what + " não é deste paciente");
    }
}
