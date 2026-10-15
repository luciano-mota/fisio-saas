package com.fisiosaas.booking;

import com.fisiosaas.agenda.Agenda;
import com.fisiosaas.booking.BookingSlots.Busy;
import com.fisiosaas.booking.BookingSlots.Hours;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Regras do agendamento online: configuração da clínica, horários livres e expiração dos pedidos pendentes. */
@Component
public class Booking {

    public record Settings(boolean enabled, int minNoticeHours, int maxDays, int holdHours, int stepMinutes) {}

    private final JdbcTemplate jdbc;
    private final Agenda agenda;

    public Booking(JdbcTemplate jdbc, Agenda agenda) {
        this.jdbc = jdbc;
        this.agenda = agenda;
    }

    public Settings settings(UUID clinicId) {
        return jdbc.queryForObject("""
                SELECT booking_enabled, booking_min_notice_hours, booking_max_days, booking_hold_hours, booking_step_minutes
                  FROM clinics WHERE id = ?""",
            (rs, n) -> new Settings(rs.getBoolean(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5)), clinicId);
    }

    public List<Hours> hours(UUID clinicId) {
        return jdbc.query("SELECT weekday, start_time, end_time FROM clinic_hours WHERE clinic_id = ? ORDER BY weekday, start_time",
            (rs, n) -> new Hours(rs.getInt(1), rs.getObject(2, LocalTime.class), rs.getObject(3, LocalTime.class)), clinicId);
    }

    /** Pedidos pendentes cujo prazo acabou deixam de segurar o horário. */
    public void expire(UUID clinicId) {
        jdbc.update("""
            UPDATE booking_requests SET status = 'EXPIRED', decided_at = now()
             WHERE clinic_id = ? AND status = 'PENDING' AND hold_until < now()""", clinicId);
    }

    public LocalDateTime now(UUID clinicId) {
        return LocalDateTime.now(agenda.zone(clinicId));
    }

    /** Horários livres por dia para um serviço de {@code minutes} minutos. Expira pedidos vencidos antes de calcular. */
    public Map<LocalDate, List<LocalTime>> freeSlots(UUID clinicId, int minutes, LocalDate from, LocalDate to) {
        expire(clinicId);
        Settings s = settings(clinicId);
        ZoneId zone = agenda.zone(clinicId);
        LocalDateTime now = LocalDateTime.now(zone);
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();
        return BookingSlots.free(hours(clinicId), busy(clinicId, start, end, zone), minutes, s.stepMinutes(), from, to,
            now.plusHours(s.minNoticeHours()), now.toLocalDate().plusDays(s.maxDays()));
    }

    /** Sessões e bloqueios não cancelados + pedidos pendentes no período. */
    private List<Busy> busy(UUID clinicId, LocalDateTime from, LocalDateTime to, ZoneId zone) {
        List<Busy> busy = new ArrayList<>();
        OffsetDateTime a = agenda.toInstant(from, zone);
        OffsetDateTime b = agenda.toInstant(to, zone);
        jdbc.query("""
                SELECT starts_at, ends_at FROM appointments
                 WHERE clinic_id = ? AND status <> 'CANCELED' AND starts_at < ? AND ends_at > ?
                UNION ALL
                SELECT starts_at, ends_at FROM booking_requests
                 WHERE clinic_id = ? AND status = 'PENDING' AND starts_at < ? AND ends_at > ?""",
            rs -> {
                busy.add(new Busy(local(rs.getObject(1, OffsetDateTime.class), zone), local(rs.getObject(2, OffsetDateTime.class), zone)));
            }, clinicId, b, a, clinicId, b, a);
        return busy;
    }

    private static LocalDateTime local(OffsetDateTime instant, ZoneId zone) {
        return instant.atZoneSameInstant(zone).toLocalDateTime();
    }
}
