package com.fisiosaas.patientpackage;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Liga sessões aos pacotes vendidos e mantém o saldo. Uma sessão ligada a um pacote:
 * - agendada/confirmada: reserva uma vaga do pacote (não consome);
 * - realizada: consome;
 * - falta: consome só se a clínica escolheu assim (gravado na sessão, no momento da mudança);
 * - cancelada, ou falta que não consome: solta o pacote (a vaga volta).
 * Nunca liga mais sessões do que o pacote tem: os pacotes do paciente ficam travados (FOR UPDATE) durante a conta.
 * Todos os métodos devem rodar dentro da transação de quem chama.
 */
@Component
public class PackageLedger {
    private static final String OPEN = "('SCHEDULED', 'CONFIRMED')";

    private final JdbcTemplate jdbc;

    public PackageLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Session(UUID patientId, UUID serviceId, UUID packageId, String status, LocalDate date) {}

    private Session session(UUID clinicId, UUID appointmentId) {
        return jdbc.queryForObject("""
                SELECT a.patient_id, a.service_id, a.patient_package_id, a.status,
                       (a.starts_at AT TIME ZONE c.timezone)::date AS local_date
                  FROM appointments a JOIN clinics c ON c.id = a.clinic_id
                 WHERE a.id = ? AND a.clinic_id = ? AND a.kind = 'SESSION'""",
            (rs, n) -> new Session(rs.getObject("patient_id", UUID.class), rs.getObject("service_id", UUID.class),
                rs.getObject("patient_package_id", UUID.class), rs.getString("status"),
                rs.getObject("local_date", LocalDate.class)),
            appointmentId, clinicId);
    }

    /** Sessão nova (avulsa ou de série): reserva vaga no pacote mais antigo do paciente para o serviço, se houver. */
    public void linkNew(UUID clinicId, UUID appointmentId) {
        Session s = session(clinicId, appointmentId);
        if (s.packageId() == null) findRoom(clinicId, s, appointmentId).ifPresent(id -> link(clinicId, appointmentId, id, false));
    }

    /** Recalcula ligação e consumo depois que o status da sessão mudou. */
    public void applyStatus(UUID clinicId, UUID appointmentId) {
        Session s = session(clinicId, appointmentId);
        boolean consumes = switch (s.status()) {
            case "DONE" -> true;
            case "NO_SHOW" -> Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT no_show_consumes_session FROM clinics WHERE id = ?", Boolean.class, clinicId));
            default -> false;
        };
        boolean open = s.status().equals("SCHEDULED") || s.status().equals("CONFIRMED");

        if (!consumes && !open) {
            unlink(clinicId, appointmentId);
            return;
        }
        UUID target = s.packageId();
        if (target != null && !hasRoom(clinicId, target, appointmentId)) target = null;
        if (target == null) target = findRoom(clinicId, s, appointmentId).orElse(null);
        if (target == null) unlink(clinicId, appointmentId);
        else link(clinicId, appointmentId, target, consumes);
    }

    /**
     * Troca manual do pacote da sessão (ou null para avulsa). O pacote precisa ser do mesmo paciente e serviço,
     * estar ativo e ter vaga.
     */
    public void assign(UUID clinicId, UUID appointmentId, UUID patientPackageId) {
        Session s = session(clinicId, appointmentId);
        if (patientPackageId == null) {
            unlink(clinicId, appointmentId);
            return;
        }
        List<UUID> own = lockCandidates(clinicId, s);
        if (!own.contains(patientPackageId)) throw new IllegalArgumentException("Pacote não é deste paciente e serviço");
        if (!hasRoom(clinicId, patientPackageId, appointmentId)) throw new IllegalArgumentException("Pacote sem sessões disponíveis");
        boolean consumes = s.status().equals("DONE") || (s.status().equals("NO_SHOW") && Boolean.TRUE.equals(
            jdbc.queryForObject("SELECT no_show_consumes_session FROM clinics WHERE id = ?", Boolean.class, clinicId)));
        boolean open = s.status().equals("SCHEDULED") || s.status().equals("CONFIRMED");
        if (!consumes && !open) throw new IllegalArgumentException("Sessão cancelada ou falta que não consome não usa pacote");
        link(clinicId, appointmentId, patientPackageId, consumes);
    }

    /** Pacote desativado: as sessões ainda em aberto ficam avulsas; as já consumidas continuam no histórico do pacote. */
    public void releaseOpen(UUID clinicId, UUID patientPackageId) {
        jdbc.update("UPDATE appointments SET patient_package_id = NULL, updated_at = now() "
                + "WHERE clinic_id = ? AND patient_package_id = ? AND status IN " + OPEN,
            clinicId, patientPackageId);
    }

    private Optional<UUID> findRoom(UUID clinicId, Session s, UUID appointmentId) {
        for (UUID candidate : lockCandidates(clinicId, s)) {
            if (hasRoom(clinicId, candidate, appointmentId)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    /** Pacotes ativos do paciente para o serviço, travados; não vencidos na data da sessão primeiro, depois os mais antigos. */
    private List<UUID> lockCandidates(UUID clinicId, Session s) {
        return jdbc.query("""
                SELECT id FROM patient_packages
                 WHERE clinic_id = ? AND patient_id = ? AND service_id = ? AND active
                 ORDER BY (expires_on IS NOT NULL AND expires_on < ?), purchased_on, created_at
                   FOR UPDATE""",
            (rs, n) -> rs.getObject("id", UUID.class), clinicId, s.patientId(), s.serviceId(), s.date());
    }

    /** Vagas = sessões do pacote − (consumidas + reservadas por sessões em aberto), sem contar a própria sessão. */
    private boolean hasRoom(UUID clinicId, UUID patientPackageId, UUID appointmentId) {
        // Pacote inativo ou de outra clínica não devolve linha: sem vaga.
        return Boolean.TRUE.equals(jdbc.query("""
                SELECT pp.session_count > (
                         SELECT count(*) FROM appointments a
                          WHERE a.clinic_id = pp.clinic_id AND a.patient_package_id = pp.id AND a.id <> ?
                            AND (a.counts_against_package OR a.status IN %s))
                  FROM patient_packages pp WHERE pp.id = ? AND pp.clinic_id = ? AND pp.active""".formatted(OPEN),
            rs -> rs.next() && rs.getBoolean(1), appointmentId, patientPackageId, clinicId));
    }

    private void link(UUID clinicId, UUID appointmentId, UUID patientPackageId, boolean consumes) {
        jdbc.update("""
            UPDATE appointments SET patient_package_id = ?, counts_against_package = ?, updated_at = now()
             WHERE id = ? AND clinic_id = ?""", patientPackageId, consumes, appointmentId, clinicId);
    }

    private void unlink(UUID clinicId, UUID appointmentId) {
        jdbc.update("""
            UPDATE appointments SET patient_package_id = NULL, counts_against_package = FALSE, updated_at = now()
             WHERE id = ? AND clinic_id = ?""", appointmentId, clinicId);
    }
}
