package com.fisiosaas.booking;

import com.fisiosaas.agenda.Agenda;
import com.fisiosaas.api.ConflictException;
import com.fisiosaas.booking.Booking.Settings;
import com.fisiosaas.patient.Cpf;
import com.fisiosaas.publicsite.PublicClinics;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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

/**
 * Agendamento pelo site, sem login. A clínica vem do endereço público; o paciente informa CPF (obrigatório) para
 * a clínica achar o cadastro dele sem duplicar. A resposta é a mesma com ou sem cadastro: o site nunca revela se um
 * CPF é paciente da clínica.
 */
@RestController
@RequestMapping("/api/v1/public/clinics/{slug}")
public class PublicBookingController {
    private static final Logger log = LoggerFactory.getLogger(PublicBookingController.class);
    static final int MAX_RANGE_DAYS = 31;
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
        String website) {}

    public record Sent(String message) {}

    private final JdbcTemplate jdbc;
    private final PublicClinics publicClinics;
    private final Booking booking;
    private final Agenda agenda;
    private final BookingThrottle throttle;

    public PublicBookingController(JdbcTemplate jdbc, PublicClinics publicClinics, Booking booking, Agenda agenda,
                                   BookingThrottle throttle) {
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
            return ResponseEntity.accepted().body(new Sent(SENT)); // robô: finge que deu certo e não grava nada
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
        try {
            jdbc.update("""
                INSERT INTO booking_requests(id, clinic_id, service_id, starts_at, ends_at, full_name, cpf, whatsapp,
                                             birth_date, notes, hold_until, patient_id, consent_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now() + make_interval(hours => ?), ?, now())""",
                UUID.randomUUID(), clinicId, body.serviceId(), agenda.toInstant(body.startsAt(), zone),
                agenda.toInstant(body.startsAt().plusMinutes(minutes), zone), body.fullName().trim().replaceAll("\\s+", " "),
                cpf, whatsapp, body.birthDate(), body.notes() == null || body.notes().isBlank() ? null : body.notes().trim(),
                s.holdHours(), patientId);
        } catch (DataIntegrityViolationException taken) {
            if (String.valueOf(taken.getMessage()).contains("booking_requests_no_overlap")) {
                throw new ConflictException("Esse horário acabou de ser reservado por outra pessoa. Escolha outro.", List.of());
            }
            throw taken;
        }
        log.info("Pedido de agendamento pelo site: clinica={} servico={} inicio={}", clinicId, body.serviceId(), body.startsAt());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new Sent(SENT));
    }

    private UUID requireBookingClinic(String slug) {
        UUID clinicId = publicClinics.requireEnabled(slug);
        if (!booking.settings(clinicId).enabled()) throw new NoSuchElementException();
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
