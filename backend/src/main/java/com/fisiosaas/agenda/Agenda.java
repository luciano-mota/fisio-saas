package com.fisiosaas.agenda;

import com.fisiosaas.api.ConflictException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Regras da agenda compartilhadas pelas sessões avulsas, recorrentes e bloqueios.
 * Horários entram e saem no fuso da clínica; no banco ficam como TIMESTAMPTZ.
 */
@Component
public class Agenda {
    static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM 'às' HH:mm");

    public enum Status { SCHEDULED, CONFIRMED, DONE, NO_SHOW, CANCELED }

    public record Slot(LocalDateTime startsAt, LocalDateTime endsAt) {
        public Slot {
            if (startsAt == null || endsAt == null || !endsAt.isAfter(startsAt)) {
                throw new IllegalArgumentException("O fim precisa ser depois do início");
            }
            if (endsAt.isAfter(startsAt.plusHours(12))) throw new IllegalArgumentException("Máximo de 12 horas");
        }
    }

    public record AppointmentView(UUID id, String kind, UUID patientId, String patientName, String patientWhatsapp,
                                  UUID serviceId, String serviceName, LocalDateTime startsAt, LocalDateTime endsAt,
                                  String status, String notes, LocalDateTime originalStartsAt, int rescheduleCount,
                                  UUID seriesId, UUID patientPackageId, String packageName, Integer packageRemaining,
                                  boolean countsAgainstPackage, BigDecimal price, BigDecimal paidAmount) {}

    private static final String SELECT = """
        SELECT a.*, p.full_name AS patient_name, p.whatsapp AS patient_whatsapp, s.name AS service_name,
               pp.name AS package_name,
               (pp.session_count - (SELECT count(*) FROM appointments x
                                     WHERE x.clinic_id = a.clinic_id AND x.patient_package_id = pp.id
                                       AND x.counts_against_package))::int AS package_remaining,
               (SELECT coalesce(sum(y.amount), 0) FROM payments y
                 WHERE y.clinic_id = a.clinic_id AND y.appointment_id = a.id AND NOT y.canceled) AS paid_amount
          FROM appointments a
          LEFT JOIN patients p ON p.id = a.patient_id AND p.clinic_id = a.clinic_id
          LEFT JOIN services s ON s.id = a.service_id AND s.clinic_id = a.clinic_id
          LEFT JOIN patient_packages pp ON pp.id = a.patient_package_id AND pp.clinic_id = a.clinic_id
        """;

    private final JdbcTemplate jdbc;

    public Agenda(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ZoneId zone(UUID clinicId) {
        return ZoneId.of(jdbc.queryForObject("SELECT timezone FROM clinics WHERE id = ?", String.class, clinicId));
    }

    public OffsetDateTime toInstant(LocalDateTime local, ZoneId zone) {
        return local.atZone(zone).toOffsetDateTime();
    }

    public List<AppointmentView> between(UUID clinicId, LocalDateTime from, LocalDateTime to, boolean includeCanceled) {
        ZoneId zone = zone(clinicId);
        return jdbc.query(SELECT + """
                 WHERE a.clinic_id = ? AND a.starts_at >= ? AND a.starts_at < ? AND (a.status <> 'CANCELED' OR ?)
                 ORDER BY a.starts_at, a.kind""",
            (rs, n) -> map(rs, zone), clinicId, toInstant(from, zone), toInstant(to, zone), includeCanceled);
    }

    public AppointmentView find(UUID clinicId, UUID id) {
        ZoneId zone = zone(clinicId);
        return jdbc.query(SELECT + " WHERE a.id = ? AND a.clinic_id = ?", (rs, n) -> map(rs, zone), id, clinicId)
            .stream().findFirst().orElseThrow(NoSuchElementException::new);
    }

    /**
     * Recusa com 409 e mensagem clara se o horário já está ocupado. O banco também impede (constraint de exclusão),
     * o que cobre dois cliques simultâneos.
     */
    public void requireFree(UUID clinicId, Slot slot, UUID ignoreId) {
        conflictWith(clinicId, slot, ignoreId).ifPresent(conflict -> {
            throw new ConflictException("Horário ocupado: conflita com " + conflict);
        });
    }

    /** Descreve o primeiro compromisso que ocupa o horário (ex.: "sessão de Maria em 07/01 às 08:00"). */
    public Optional<String> conflictWith(UUID clinicId, Slot slot, UUID ignoreId) {
        ZoneId zone = zone(clinicId);
        return jdbc.query("""
                SELECT a.kind, a.starts_at, a.notes, p.full_name FROM appointments a
                  LEFT JOIN patients p ON p.id = a.patient_id AND p.clinic_id = a.clinic_id
                 WHERE a.clinic_id = ? AND a.status <> 'CANCELED' AND a.id <> coalesce(?::uuid, '00000000-0000-0000-0000-000000000000'::uuid)
                   AND tstzrange(a.starts_at, a.ends_at) && tstzrange(?, ?)
                 ORDER BY a.starts_at LIMIT 1""",
            (rs, n) -> {
                String when = rs.getObject("starts_at", OffsetDateTime.class).atZoneSameInstant(zone).format(WHEN);
                return "SESSION".equals(rs.getString("kind"))
                    ? "sessão de " + rs.getString("full_name") + " em " + when
                    : "horário bloqueado em " + when + (rs.getString("notes") == null ? "" : " (" + rs.getString("notes") + ")");
            },
            clinicId, ignoreId, toInstant(slot.startsAt(), zone), toInstant(slot.endsAt(), zone))
            .stream().findFirst();
    }

    /** Executa a escrita traduzindo a violação da constraint de exclusão (corrida entre dois cliques) em 409. */
    public void write(Runnable update) {
        try {
            update.run();
        } catch (DataIntegrityViolationException error) {
            if (String.valueOf(error.getMessage()).contains("appointments_no_overlap")) {
                throw new ConflictException("Horário ocupado: outra marcação foi feita neste horário agora há pouco");
            }
            throw error;
        }
    }

    public void requireOwnPatient(UUID clinicId, UUID patientId) {
        Integer found = jdbc.queryForObject("SELECT count(*) FROM patients WHERE id = ? AND clinic_id = ? AND active",
            Integer.class, patientId, clinicId);
        if (found == null || found == 0) throw new IllegalArgumentException("Paciente não encontrado");
    }

    /** Devolve a duração padrão do serviço. */
    public int requireOwnService(UUID clinicId, UUID serviceId) {
        return jdbc.query("SELECT duration_minutes FROM services WHERE id = ? AND clinic_id = ? AND active",
                (rs, n) -> rs.getInt(1), serviceId, clinicId)
            .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Serviço não encontrado"));
    }

    private static AppointmentView map(java.sql.ResultSet rs, ZoneId zone) throws java.sql.SQLException {
        OffsetDateTime original = rs.getObject("original_starts_at", OffsetDateTime.class);
        return new AppointmentView(
            rs.getObject("id", UUID.class), rs.getString("kind"),
            rs.getObject("patient_id", UUID.class), rs.getString("patient_name"), rs.getString("patient_whatsapp"),
            rs.getObject("service_id", UUID.class), rs.getString("service_name"),
            local(rs.getObject("starts_at", OffsetDateTime.class), zone),
            local(rs.getObject("ends_at", OffsetDateTime.class), zone),
            rs.getString("status"), rs.getString("notes"),
            original == null ? null : local(original, zone), rs.getInt("reschedule_count"),
            rs.getObject("series_id", UUID.class), rs.getObject("patient_package_id", UUID.class),
            rs.getString("package_name"), (Integer) rs.getObject("package_remaining"),
            rs.getBoolean("counts_against_package"), rs.getBigDecimal("price"), rs.getBigDecimal("paid_amount"));
    }

    private static LocalDateTime local(OffsetDateTime instant, ZoneId zone) {
        return instant.atZoneSameInstant(zone).toLocalDateTime();
    }
}
