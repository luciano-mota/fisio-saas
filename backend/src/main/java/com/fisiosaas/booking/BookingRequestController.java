package com.fisiosaas.booking;

import com.fisiosaas.agenda.Agenda;
import com.fisiosaas.agenda.Sessions;
import com.fisiosaas.api.ConflictException;
import com.fisiosaas.finance.CardFees.Method;
import com.fisiosaas.finance.PaymentController;
import com.fisiosaas.finance.PaymentController.PaymentRequest;
import com.fisiosaas.identity.CurrentClinic;
import com.fisiosaas.patient.PatientController;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Solicitações de agendamento feitas pelo site (painel). Ao aceitar: o CPF acha o paciente já cadastrado (sem
 * duplicar); sem cadastro, cria o paciente; e a sessão entra na agenda (com pacote, se o paciente tiver).
 * Nada é apagado: recusadas e expiradas ficam com o status.
 */
@RestController
@RequestMapping("/api/v1/booking-requests")
public class BookingRequestController {

    /** Paciente do cadastro que bate com o pedido (pelo CPF) ou possível mesmo paciente (mesmo WhatsApp, sem CPF). */
    public record PatientMatch(UUID id, String fullName, String whatsapp, LocalDate birthDate, boolean active) {}

    public record RequestView(UUID id, UUID serviceId, String serviceName, BigDecimal servicePrice, LocalDateTime startsAt,
                              LocalDateTime endsAt, String fullName, String cpf, String whatsapp, LocalDate birthDate,
                              String notes, String status, LocalDateTime holdUntil, LocalDateTime createdAt,
                              String declineReason, UUID appointmentId, PatientMatch patient, List<PatientMatch> suggestions,
                              String code, String payMethod, BigDecimal payAmount, Receipt receipt, UUID paymentId) {}

    /** Comprovante do Pix enviado pelo paciente (o arquivo em si vem por /receipt). */
    public record Receipt(String contentType, int sizeBytes, LocalDateTime uploadedAt) {}

    /**
     * Aceite. {@code linkPatientId}: paciente antigo sem CPF que é a mesma pessoa (recebe o CPF do pedido).
     * {@code updatePatient}: atualiza nome/WhatsApp/nascimento do cadastro com o que veio no pedido.
     */
    public record AcceptRequest(UUID linkPatientId, Boolean updatePatient, Boolean confirmPayment) {}

    public record DeclineRequest(@Size(max = 300) String reason) {}

    public record Count(int pending) {}

    private final JdbcTemplate jdbc;
    private final CurrentClinic current;
    private final Booking booking;
    private final Agenda agenda;
    private final Sessions sessions;
    private final PaymentController payments;

    public BookingRequestController(JdbcTemplate jdbc, CurrentClinic current, Booking booking, Agenda agenda, Sessions sessions,
                                    PaymentController payments) {
        this.payments = payments;
        this.jdbc = jdbc;
        this.current = current;
        this.booking = booking;
        this.agenda = agenda;
        this.sessions = sessions;
    }

    /** {@code status=PENDING} (padrão) ou {@code ALL} (últimos 60 dias). */
    @GetMapping
    @Transactional
    public List<RequestView> list(Authentication auth, @RequestParam(defaultValue = "PENDING") String status) {
        UUID clinicId = current.require(auth).clinicId();
        booking.expire(clinicId);
        boolean all = "ALL".equals(status);
        List<UUID> ids = jdbc.queryForList("""
                SELECT id FROM booking_requests
                 WHERE clinic_id = ? AND (status = 'PENDING' OR (? AND created_at > now() - INTERVAL '60 days'))
                 ORDER BY CASE WHEN status = 'PENDING' THEN 0 ELSE 1 END, starts_at""", UUID.class, clinicId, all);
        return ids.stream().map(id -> view(clinicId, id)).toList();
    }

    @GetMapping("/count")
    @Transactional
    public Count count(Authentication auth) {
        UUID clinicId = current.require(auth).clinicId();
        booking.expire(clinicId);
        Integer n = jdbc.queryForObject("SELECT count(*) FROM booking_requests WHERE clinic_id = ? AND status = 'PENDING'",
            Integer.class, clinicId);
        return new Count(n == null ? 0 : n);
    }

    @GetMapping("/{id}")
    public RequestView get(Authentication auth, @PathVariable UUID id) {
        return view(current.require(auth).clinicId(), id);
    }

    @PostMapping("/{id}/accept")
    @Transactional
    public RequestView accept(Authentication auth, @PathVariable UUID id, @RequestBody(required = false) AcceptRequest body) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        booking.expire(clinicId);
        RequestView req = lock(clinicId, id);
        // Expirado ainda pode ser aceito se o horário estiver livre (a clínica só demorou a responder).
        if (!req.status().equals("PENDING") && !req.status().equals("EXPIRED")) {
            throw new ConflictException("Esta solicitação já foi respondida", List.of());
        }
        if (!req.startsAt().isAfter(booking.now(clinicId))) throw new IllegalArgumentException("O horário pedido já passou");
        boolean update = body != null && Boolean.TRUE.equals(body.updatePatient());

        UUID patientId;
        if (req.patient() != null) {
            patientId = req.patient().id();
            jdbc.update("UPDATE patients SET active = TRUE, updated_at = now() WHERE id = ? AND clinic_id = ?", patientId, clinicId);
            if (update) updatePatient(clinicId, patientId, req);
        } else if (body != null && body.linkPatientId() != null) {
            patientId = body.linkPatientId();
            Integer ok = jdbc.queryForObject(
                "SELECT count(*) FROM patients WHERE id = ? AND clinic_id = ? AND (cpf IS NULL OR cpf = ?)",
                Integer.class, patientId, clinicId, req.cpf());
            if (ok == null || ok == 0) throw new IllegalArgumentException("Paciente para ligar não encontrado ou já tem outro CPF");
            jdbc.update("UPDATE patients SET cpf = ?, active = TRUE, updated_at = now() WHERE id = ? AND clinic_id = ?",
                req.cpf(), patientId, clinicId);
            if (update) updatePatient(clinicId, patientId, req);
        } else {
            patientId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO patients(id, clinic_id, full_name, search_name, cpf, whatsapp, birth_date, admin_notes, active)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'Cadastro pelo agendamento do site', TRUE)""",
                patientId, clinicId, req.fullName(), PatientController.searchKey(req.fullName()), req.cpf(), req.whatsapp(),
                req.birthDate());
        }

        UUID appointmentId = sessions.create(clinicId, patientId, req.serviceId(), req.startsAt(),
            (int) java.time.Duration.between(req.startsAt(), req.endsAt()).toMinutes(), req.notes());
        // Pix conferido no banco: lança o pagamento no financeiro (na sessão, se for avulsa).
        UUID paymentId = null;
        if (body != null && Boolean.TRUE.equals(body.confirmPayment()) && "PIX".equals(req.payMethod())) {
            Boolean inPackage = jdbc.queryForObject("SELECT patient_package_id IS NOT NULL FROM appointments WHERE id = ? AND clinic_id = ?",
                Boolean.class, appointmentId, clinicId);
            paymentId = payments.register(clinicId, new PaymentRequest(patientId, null,
                Boolean.TRUE.equals(inPackage) ? null : appointmentId, Method.PIX, null, 1, req.payAmount(), null,
                "Pix do agendamento pelo site " + req.code())).id();
        }
        jdbc.update("""
            UPDATE booking_requests SET status = 'ACCEPTED', patient_id = ?, appointment_id = ?, payment_id = ?, decided_at = now()
             WHERE id = ? AND clinic_id = ?""", patientId, appointmentId, paymentId, id, clinicId);
        return view(clinicId, id);
    }

    /** Arquivo do comprovante (só a responsável). Servido sem permitir script, mesmo se o PDF tiver. */
    @GetMapping("/{id}/receipt")
    public ResponseEntity<byte[]> receipt(Authentication auth, @PathVariable UUID id) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        return jdbc.query("SELECT content_type, data FROM booking_receipts WHERE request_id = ? AND clinic_id = ?",
                (rs, n) -> ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(rs.getString(1)))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"comprovante\"")
                    .header("Content-Security-Policy", "sandbox")
                    .header("X-Content-Type-Options", "nosniff")
                    .cacheControl(CacheControl.noStore())
                    .body(rs.getBytes(2)), id, clinicId)
            .stream().findFirst().orElseThrow(NoSuchElementException::new);
    }

    @PostMapping("/{id}/decline")
    @Transactional
    public RequestView decline(Authentication auth, @PathVariable UUID id, @RequestBody(required = false) DeclineRequest body) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        booking.expire(clinicId);
        RequestView req = lock(clinicId, id);
        if (!req.status().equals("PENDING") && !req.status().equals("EXPIRED")) {
            throw new ConflictException("Esta solicitação já foi respondida", List.of());
        }
        String reason = body == null || body.reason() == null || body.reason().isBlank() ? null : body.reason().trim();
        jdbc.update("""
            UPDATE booking_requests SET status = 'DECLINED', decline_reason = ?, decided_at = now()
             WHERE id = ? AND clinic_id = ?""", reason, id, clinicId);
        return view(clinicId, id);
    }

    private void updatePatient(UUID clinicId, UUID patientId, RequestView req) {
        jdbc.update("""
            UPDATE patients SET full_name = ?, search_name = ?, whatsapp = ?, birth_date = coalesce(?, birth_date),
                   updated_at = now() WHERE id = ? AND clinic_id = ?""",
            req.fullName(), PatientController.searchKey(req.fullName()), req.whatsapp(), req.birthDate(), patientId, clinicId);
    }

    /** Trava a linha para dois cliques em "aceitar" não criarem duas sessões. */
    private RequestView lock(UUID clinicId, UUID id) {
        Integer found = jdbc.queryForObject("SELECT count(*) FROM (SELECT 1 FROM booking_requests WHERE id = ? AND clinic_id = ? FOR UPDATE) x",
            Integer.class, id, clinicId);
        if (found == null || found == 0) throw new NoSuchElementException();
        return view(clinicId, id);
    }

    private RequestView view(UUID clinicId, UUID id) {
        ZoneId zone = agenda.zone(clinicId);
        RequestView base = jdbc.query("""
                SELECT r.*, s.name AS service_name, CASE WHEN s.show_price THEN s.price END AS service_price,
                       b.content_type AS receipt_type, b.size_bytes AS receipt_size, b.uploaded_at AS receipt_at
                  FROM booking_requests r JOIN services s ON s.id = r.service_id AND s.clinic_id = r.clinic_id
                  LEFT JOIN booking_receipts b ON b.request_id = r.id AND b.clinic_id = r.clinic_id
                 WHERE r.id = ? AND r.clinic_id = ?""",
            (rs, n) -> new RequestView(rs.getObject("id", UUID.class), rs.getObject("service_id", UUID.class),
                rs.getString("service_name"), rs.getBigDecimal("service_price"),
                local(rs.getObject("starts_at", OffsetDateTime.class), zone), local(rs.getObject("ends_at", OffsetDateTime.class), zone),
                rs.getString("full_name"), rs.getString("cpf"), rs.getString("whatsapp"), rs.getObject("birth_date", LocalDate.class),
                rs.getString("notes"), rs.getString("status"), local(rs.getObject("hold_until", OffsetDateTime.class), zone),
                local(rs.getObject("created_at", OffsetDateTime.class), zone), rs.getString("decline_reason"),
                rs.getObject("appointment_id", UUID.class), null, List.of(), rs.getString("code"), rs.getString("pay_method"),
                rs.getBigDecimal("pay_amount"), rs.getString("receipt_type") == null ? null
                    : new Receipt(rs.getString("receipt_type"), rs.getInt("receipt_size"),
                        local(rs.getObject("receipt_at", OffsetDateTime.class), zone)),
                rs.getObject("payment_id", UUID.class)),
            id, clinicId).stream().findFirst().orElseThrow(NoSuchElementException::new);

        PatientMatch byCpf = jdbc.query("""
                SELECT id, full_name, whatsapp, birth_date, active FROM patients WHERE clinic_id = ? AND cpf = ?""",
            (rs, n) -> new PatientMatch(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getObject(4, LocalDate.class), rs.getBoolean(5)), clinicId, base.cpf()).stream().findFirst().orElse(null);
        List<PatientMatch> suggestions = byCpf != null ? List.of() : jdbc.query("""
                SELECT id, full_name, whatsapp, birth_date, active FROM patients
                 WHERE clinic_id = ? AND cpf IS NULL AND whatsapp = ? ORDER BY active DESC, full_name LIMIT 3""",
            (rs, n) -> new PatientMatch(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getObject(4, LocalDate.class), rs.getBoolean(5)), clinicId, base.whatsapp());
        return new RequestView(base.id(), base.serviceId(), base.serviceName(), base.servicePrice(), base.startsAt(),
            base.endsAt(), base.fullName(), base.cpf(), base.whatsapp(), base.birthDate(), base.notes(), base.status(),
            base.holdUntil(), base.createdAt(), base.declineReason(), base.appointmentId(), byCpf, suggestions, base.code(),
            base.payMethod(), base.payAmount(), base.receipt(), base.paymentId());
    }

    private static LocalDateTime local(OffsetDateTime instant, ZoneId zone) {
        return instant == null ? null : instant.atZoneSameInstant(zone).toLocalDateTime();
    }
}
