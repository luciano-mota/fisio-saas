package com.fisiosaas.booking;

import com.fisiosaas.agenda.Agenda;
import com.fisiosaas.api.ConflictException;
import com.fisiosaas.booking.Booking.Settings;
import com.fisiosaas.clinic.Images;
import com.fisiosaas.patient.Cpf;
import com.fisiosaas.pix.PixCode;
import com.fisiosaas.pix.PixController.Pix;
import com.fisiosaas.publicsite.PublicClinics;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Agendamento pelo site, sem login. A clínica vem do endereço público; o paciente informa CPF (obrigatório) para
 * a clínica achar o cadastro dele sem duplicar. A resposta é a mesma com ou sem cadastro: o site nunca revela se um
 * CPF é paciente da clínica.
 */
@RestController
@RequestMapping("/api/v1/public/clinics/{slug}")
public class PublicBookingController {
    private static final Logger log = LoggerFactory.getLogger(PublicBookingController.class);
    /** Até o máximo configurável de dias à frente (180) mais hoje. */
    static final int MAX_RANGE_DAYS = 181;
    static final int MAX_PENDING_PER_CPF = 2;
    static final String SENT = "Pedido enviado! A clínica vai confirmar o horário pelo WhatsApp.";

    public record BookingRequestBody(
        @NotNull UUID serviceId,
        @NotNull LocalDateTime startsAt,
        @NotBlank @Size(max = 120) String fullName,
        @NotBlank @Size(max = 14) String cpf,
        @NotBlank @Size(max = 20) String whatsapp,
        LocalDate birthDate,
        @Size(max = 500) String notes,
        Boolean consent,
        /** Campo escondido no formulário: só robôs preenchem. */
        String website,
        /** PIX (paga agora e envia comprovante) ou CLINICA; só vale quando o serviço tem preço visível. */
        String payMethod) {}

    /**
     * Resposta do pedido. Com Pix: código do pedido, "copia e cola", QR Code e o token para enviar o comprovante
     * (o mesmo para quem já é ou não paciente — nada aqui depende do cadastro).
     */
    public record Sent(String message, UUID requestId, String code, BigDecimal amount, String pixCode, String qrSvg,
                       String receiptToken) {
        static Sent plain() {
            return new Sent(SENT, null, null, null, null, null, null);
        }
    }

    public record ReceiptSent(String message) {}

    private final JdbcTemplate jdbc;
    private final PublicClinics publicClinics;
    private final Booking booking;
    private final Agenda agenda;
    private final BookingThrottle throttle;
    private final Pix pix;

    public PublicBookingController(JdbcTemplate jdbc, PublicClinics publicClinics, Booking booking, Agenda agenda,
                                   BookingThrottle throttle, Pix pix) {
        this.pix = pix;
        this.jdbc = jdbc;
        this.publicClinics = publicClinics;
        this.booking = booking;
        this.agenda = agenda;
        this.throttle = throttle;
    }

    /** Horários livres do serviço, por dia ({"2026-10-16": ["08:00", "08:30"]}). Não diz quem ocupa o resto. */
    @GetMapping("/slots")
    @Transactional
    public Map<LocalDate, List<String>> slots(@PathVariable String slug, @RequestParam UUID serviceId,
                                                 @RequestParam LocalDate from, @RequestParam LocalDate to) {
        UUID clinicId = requireBookingClinic(slug);
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("Período inválido");
        }
        Map<LocalDate, List<String>> result = new java.util.TreeMap<>();
        booking.freeSlots(clinicId, bookableService(clinicId, serviceId), from, to)
            .forEach((day, times) -> result.put(day, times.stream().map(t -> t.toString().substring(0, 5)).toList()));
        return result;
    }

    @PostMapping("/booking-requests")
    @Transactional
    public ResponseEntity<Sent> request(@PathVariable String slug, @Valid @RequestBody BookingRequestBody body,
                                        HttpServletRequest http) {
        UUID clinicId = requireBookingClinic(slug);
        if (body.website() != null && !body.website().isBlank()) {
            return ResponseEntity.accepted().body(Sent.plain()); // robô: finge que deu certo e não grava nada
        }
        if (!throttle.allow(http.getRemoteAddr())) {
            throw new IllegalArgumentException("Muitos pedidos seguidos. Tente de novo mais tarde ou chame a clínica no WhatsApp.");
        }
        if (!Boolean.TRUE.equals(body.consent())) {
            throw new IllegalArgumentException("Para enviar, concorde com o uso dos seus dados para o agendamento");
        }
        String cpf = body.cpf().replaceAll("\\D", "");
        if (!Cpf.isValid(cpf)) throw new IllegalArgumentException("CPF inválido. Confira os números.");
        String whatsapp = body.whatsapp().replaceAll("\\D", "");
        if (whatsapp.length() < 10 || whatsapp.length() > 13) {
            throw new IllegalArgumentException("WhatsApp inválido: informe DDD e número");
        }
        LocalDate today = booking.now(clinicId).toLocalDate();
        if (body.birthDate() != null && (body.birthDate().isAfter(today) || body.birthDate().isBefore(LocalDate.of(1900, 1, 1)))) {
            throw new IllegalArgumentException("Data de nascimento inválida");
        }

        int minutes = bookableService(clinicId, body.serviceId());
        BigDecimal visiblePrice = jdbc.queryForObject(
            "SELECT CASE WHEN show_price THEN price END FROM services WHERE id = ? AND clinic_id = ?",
            BigDecimal.class, body.serviceId(), clinicId);
        boolean pixChosen = "PIX".equals(body.payMethod());
        if (pixChosen && (visiblePrice == null || visiblePrice.signum() <= 0 || !pix.enabled(clinicId))) {
            throw new IllegalArgumentException("Pagamento por Pix não disponível para este atendimento");
        }
        String payMethod = visiblePrice == null ? null : pixChosen ? "PIX" : "CLINICA";
        LocalDate day = body.startsAt().toLocalDate();
        List<LocalTime> free = booking.freeSlots(clinicId, minutes, day, day).getOrDefault(day, List.of());
        if (!free.contains(body.startsAt().toLocalTime())) {
            throw new ConflictException("Esse horário não está mais disponível. Escolha outro.", List.of());
        }
        Integer pending = jdbc.queryForObject(
            "SELECT count(*) FROM booking_requests WHERE clinic_id = ? AND cpf = ? AND status = 'PENDING'",
            Integer.class, clinicId, cpf);
        if (pending != null && pending >= MAX_PENDING_PER_CPF) {
            throw new IllegalArgumentException("Você já tem pedidos aguardando resposta. Aguarde a clínica confirmar.");
        }

        Settings s = booking.settings(clinicId);
        ZoneId zone = agenda.zone(clinicId);
        // Paciente já cadastrado com este CPF? Fica ligado ao pedido (só a clínica vê).
        UUID patientId = jdbc.query("SELECT id FROM patients WHERE clinic_id = ? AND cpf = ?",
            (rs, n) -> rs.getObject(1, UUID.class), clinicId, cpf).stream().findFirst().orElse(null);
        UUID requestId = UUID.randomUUID();
        String code = RequestCodes.next();
        String token = pixChosen ? RequestCodes.token() : null;
        try {
            jdbc.update("""
                INSERT INTO booking_requests(id, clinic_id, service_id, starts_at, ends_at, full_name, cpf, whatsapp,
                                             birth_date, notes, hold_until, patient_id, consent_at, pay_method, pay_amount,
                                             code, receipt_token_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now() + make_interval(hours => ?), ?, now(), ?, ?, ?, ?)""",
                requestId, clinicId, body.serviceId(), agenda.toInstant(body.startsAt(), zone),
                agenda.toInstant(body.startsAt().plusMinutes(minutes), zone), body.fullName().trim().replaceAll("\\s+", " "),
                cpf, whatsapp, body.birthDate(), body.notes() == null || body.notes().isBlank() ? null : body.notes().trim(),
                s.holdHours(), patientId, payMethod, payMethod == null ? null : visiblePrice, code,
                token == null ? null : RequestCodes.sha256(token));
        } catch (DataIntegrityViolationException taken) {
            if (String.valueOf(taken.getMessage()).contains("booking_requests_no_overlap")) {
                throw new ConflictException("Esse horário acabou de ser reservado por outra pessoa. Escolha outro.", List.of());
            }
            throw taken;
        }
        log.info("Pedido de agendamento pelo site: clinica={} servico={} inicio={} pagamento={}", clinicId, body.serviceId(),
            body.startsAt(), payMethod);
        if (!pixChosen) return ResponseEntity.status(HttpStatus.ACCEPTED).body(Sent.plain());
        String pixCode = pix.code(clinicId, visiblePrice, code);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new Sent(SENT, requestId, code, visiblePrice, pixCode,
            PixCode.qrSvg(pixCode), token));
    }

    /**
     * Comprovante do Pix (JPEG, PNG ou PDF até 3 MB), enviado por quem fez o pedido (token). Com comprovante, o
     * horário fica reservado por pelo menos mais 48 h, até a clínica conferir no banco.
     */
    @PostMapping(path = "/booking-requests/{id}/receipt", consumes = "multipart/form-data")
    @Transactional
    public ReceiptSent receipt(@PathVariable String slug, @PathVariable UUID id, @RequestParam String token,
                               @RequestParam("file") MultipartFile file) throws java.io.IOException {
        UUID clinicId = publicClinics.requireEnabled(slug);
        booking.expire(clinicId);
        var row = jdbc.query("SELECT status, receipt_token_hash FROM booking_requests WHERE id = ? AND clinic_id = ? FOR UPDATE",
            (rs, n) -> new String[] {rs.getString(1), rs.getString(2)}, id, clinicId).stream().findFirst()
            .orElseThrow(NoSuchElementException::new);
        if (row[1] == null || !java.security.MessageDigest.isEqual(row[1].getBytes(), RequestCodes.sha256(token).getBytes())) {
            throw new NoSuchElementException();
        }
        if (!"PENDING".equals(row[0])) {
            throw new IllegalArgumentException("Este pedido não está mais aguardando pagamento. Chame a clínica no WhatsApp.");
        }
        if (file.isEmpty() || file.getSize() > MAX_RECEIPT_BYTES) {
            throw new IllegalArgumentException("Envie uma foto ou PDF do comprovante de até 3 MB");
        }
        byte[] data = file.getBytes();
        String type = Images.isJpeg(data) ? "image/jpeg" : Images.isPng(data) ? "image/png" : isPdf(data) ? "application/pdf" : null;
        if (type == null) throw new IllegalArgumentException("Envie o comprovante como foto (JPG ou PNG) ou PDF");
        if (type.equals("image/jpeg")) data = Images.stripJpegMetadata(data);
        jdbc.update("DELETE FROM booking_receipts WHERE request_id = ? AND clinic_id = ?", id, clinicId);
        jdbc.update("INSERT INTO booking_receipts(request_id, clinic_id, content_type, data, size_bytes) VALUES (?, ?, ?, ?, ?)",
            id, clinicId, type, data, data.length);
        jdbc.update("UPDATE booking_requests SET hold_until = greatest(hold_until, now() + INTERVAL '48 hours') WHERE id = ? AND clinic_id = ?",
            id, clinicId);
        log.info("Comprovante Pix recebido: clinica={} pedido={} tipo={} bytes={}", clinicId, id, type, data.length);
        return new ReceiptSent("Comprovante enviado! A clínica vai conferir e confirmar pelo WhatsApp.");
    }

    static final long MAX_RECEIPT_BYTES = 3L * 1024 * 1024;

    private static boolean isPdf(byte[] data) {
        return data.length > 5 && data[0] == '%' && data[1] == 'P' && data[2] == 'D' && data[3] == 'F' && data[4] == '-';
    }

    private UUID requireBookingClinic(String slug) {
        UUID clinicId = publicClinics.requireEnabled(slug);
        // Agendamento desligado, ou clínica suspensa/vencida (o site fica no ar só como vitrine).
        if (!booking.settings(clinicId).enabled() || !publicClinics.operational(clinicId)) throw new NoSuchElementException();
        return clinicId;
    }

    /** Duração do serviço, se ele estiver ativo e aceitar agendamento online. */
    private int bookableService(UUID clinicId, UUID serviceId) {
        return jdbc.query("""
                SELECT duration_minutes FROM services
                 WHERE id = ? AND clinic_id = ? AND active AND online_booking_enabled""",
                (rs, n) -> rs.getInt(1), serviceId, clinicId)
            .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Serviço não disponível para agendamento online"));
    }
}
