package com.fisiosaas.booking;

import com.fisiosaas.booking.Booking.Settings;
import com.fisiosaas.booking.BookingSlots.Hours;
import com.fisiosaas.identity.CurrentClinic;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Horário de atendimento e regras do agendamento online (painel). Salvar substitui todos os intervalos. */
@RestController
@RequestMapping("/api/v1/clinic/booking")
public class BookingSettingsController {
    static final String[] DAY_NAMES = {"", "Na segunda", "Na terça", "Na quarta", "Na quinta", "Na sexta", "No sábado", "No domingo"};

    public record Interval(@NotNull @Min(1) @Max(7) Integer weekday, @NotNull LocalTime start, @NotNull LocalTime end) {}

    public record BookingConfig(
        @NotNull Boolean enabled,
        @NotNull @Min(0) @Max(168) Integer minNoticeHours,
        @NotNull @Min(1) @Max(180) Integer maxDays,
        @NotNull @Min(1) @Max(168) Integer holdHours,
        @NotNull Integer stepMinutes,
        @NotNull @Size(max = 28) List<@Valid @NotNull Interval> hours) {}

    private final JdbcTemplate jdbc;
    private final CurrentClinic current;
    private final Booking booking;

    public BookingSettingsController(JdbcTemplate jdbc, CurrentClinic current, Booking booking) {
        this.jdbc = jdbc;
        this.current = current;
        this.booking = booking;
    }

    @GetMapping
    public BookingConfig get(Authentication auth) {
        return load(current.require(auth).clinicId());
    }

    @PutMapping
    @Transactional
    public BookingConfig save(Authentication auth, @Valid @RequestBody BookingConfig body) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        if (!List.of(15, 20, 30, 60).contains(body.stepMinutes())) {
            throw new IllegalArgumentException("Intervalo entre horários inválido");
        }
        List<Interval> sorted = body.hours().stream()
            .sorted(Comparator.comparing(Interval::weekday).thenComparing(Interval::start)).toList();
        for (int i = 0; i < sorted.size(); i++) {
            Interval it = sorted.get(i);
            if (!it.end().isAfter(it.start())) {
                throw new IllegalArgumentException(DAY_NAMES[it.weekday()] + ", o fim precisa ser depois do início");
            }
            if (i > 0 && sorted.get(i - 1).weekday().equals(it.weekday()) && sorted.get(i - 1).end().isAfter(it.start())) {
                throw new IllegalArgumentException(DAY_NAMES[it.weekday()] + ", dois intervalos se sobrepõem");
            }
        }
        if (body.enabled() && sorted.isEmpty()) {
            throw new IllegalArgumentException("Cadastre o horário de atendimento antes de ativar o agendamento pelo site");
        }
        jdbc.update("""
            UPDATE clinics SET booking_enabled = ?, booking_min_notice_hours = ?, booking_max_days = ?,
                   booking_hold_hours = ?, booking_step_minutes = ?, updated_at = now() WHERE id = ?""",
            body.enabled(), body.minNoticeHours(), body.maxDays(), body.holdHours(), body.stepMinutes(), clinicId);
        jdbc.update("DELETE FROM clinic_hours WHERE clinic_id = ?", clinicId);
        for (Interval it : sorted) {
            jdbc.update("INSERT INTO clinic_hours(id, clinic_id, weekday, start_time, end_time) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), clinicId, it.weekday(), it.start(), it.end());
        }
        return load(clinicId);
    }

    private BookingConfig load(UUID clinicId) {
        Settings s = booking.settings(clinicId);
        List<Interval> hours = booking.hours(clinicId).stream()
            .map((Hours h) -> new Interval(h.weekday(), h.start(), h.end())).toList();
        return new BookingConfig(s.enabled(), s.minNoticeHours(), s.maxDays(), s.holdHours(), s.stepMinutes(), hours);
    }
}
