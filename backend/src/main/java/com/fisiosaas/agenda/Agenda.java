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
import java.util.stream.Collectors;
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

    /** Quantos atendimentos a clínica faz ao mesmo tempo (Clínica → Horário de atendimento). */
    public int capacity(UUID clinicId) {
        return jdbc.queryForObject("SELECT max_simultaneous FROM clinics WHERE id = ?", Integer.class, clinicId);
    }

    /**
     * Trava a agenda da clínica até o fim da transação: quem confere vaga e grava faz isso um de cada vez, para dois
     * cliques ao mesmo tempo não passarem do limite. Outras clínicas não esperam.
     */
    public void lock(UUID clinicId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {}, "agenda:" + clinicId);
    }

    /** Recusa com 409 e mensagem clara se o horário não tem vaga (ou está bloqueado). Chamar dentro da transação. */
    public void requireFree(UUID clinicId, Slot slot, UUID ignoreId, UUID patientId) {
        conflictWith(clinicId, slot, ignoreId, patientId).ifPresent(conflict -> {
            throw new ConflictException(conflict.startsWith("já tem") ? "Horário lotado: " + conflict
                : "Horário ocupado: conflita com " + conflict);
        });
    }

    /**
     * Por que o horário não serve, se não servir. {@code patientId} null = bloqueio, que não convive com nada; sessão
     * convive com outras sessões até o limite de atendimentos simultâneos da clínica, mas nunca com outra sessão do
     * mesmo paciente. Ex.: "sessão de Maria em 07/01 às 08:00" ou "já tem 2 atendimentos ao mesmo tempo (Maria, João)".
     * Trava a agenda da clínica (ver {@link #lock}).
     */
    public Optional<String> conflictWith(UUID clinicId, Slot slot, UUID ignoreId, UUID patientId) {
        boolean block = patientId == null;
        lock(clinicId);
        ZoneId zone = zone(clinicId);
        record Item(String kind, LocalDateTime start, LocalDateTime end, String notes, UUID patientId, String patient) {}
        List<Item> items = jdbc.query("""
                SELECT a.kind, a.starts_at, a.ends_at, a.notes, a.patient_id, p.full_name FROM appointments a
                  LEFT JOIN patients p ON p.id = a.patient_id AND p.clinic_id = a.clinic_id
                 WHERE a.clinic_id = ? AND a.status <> 'CANCELED' AND a.id <> coalesce(?::uuid, '00000000-0000-0000-0000-000000000000'::uuid)
                   AND tstzrange(a.starts_at, a.ends_at) && tstzrange(?, ?)
                 ORDER BY a.starts_at""",
            (rs, n) -> new Item(rs.getString("kind"),
                rs.getObject("starts_at", OffsetDateTime.class).atZoneSameInstant(zone).toLocalDateTime(),
                rs.getObject("ends_at", OffsetDateTime.class).atZoneSameInstant(zone).toLocalDateTime(),
                rs.getString("notes"), rs.getObject("patient_id", UUID.class), rs.getString("full_name")),
            clinicId, ignoreId, toInstant(slot.startsAt(), zone), toInstant(slot.endsAt(), zone));
        Optional<String> blocking = items.stream()
            .filter(i -> block || "BLOCK".equals(i.kind()) || patientId.equals(i.patientId())).findFirst()
            .map(i -> describe(i.kind(), i.start(), i.notes(), i.patient()));
        if (blocking.isPresent()) return blocking;
        int capacity = capacity(clinicId);
        List<Occupancy.Busy> sessions = items.stream().map(i -> new Occupancy.Busy(i.start(), i.end())).toList();
        if (Occupancy.fits(sessions, slot.startsAt(), slot.endsAt(), capacity)) return Optional.empty();
        if (capacity == 1) return Optional.of(describe("SESSION", items.get(0).start(), null, items.get(0).patient()));
        String names = items.stream().map(Item::patient).distinct().limit(4).collect(Collectors.joining(", "));
        return Optional.of("já tem " + capacity + " atendimentos ao mesmo tempo (" + names + ")");
    }

    private static String describe(String kind, LocalDateTime start, String notes, String patient) {
        String when = start.format(WHEN);
        return "SESSION".equals(kind) ? "sessão de " + patient + " em " + when
            : "horário bloqueado em " + when + (notes == null ? "" : " (" + notes + ")");
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
