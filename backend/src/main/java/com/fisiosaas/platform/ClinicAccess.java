package com.fisiosaas.platform;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Situação de acesso da clínica (assinatura). Vencida ou suspensa: só consulta. Bloqueada: sem acesso.
 * Os dados nunca são apagados.
 */
@Component
public class ClinicAccess {
    /** Dias antes do vencimento em que a clínica começa a ser avisada. */
    public static final int WARN_DAYS = 7;

    /** Condição SQL (alias {@code c}) da clínica que funciona normalmente: aceita agendamento e alterações. */
    public static final String OPERATIONAL_SQL = """
        c.status IN ('TRIAL', 'ACTIVE', 'FREE')
        AND (c.access_until IS NULL OR c.access_until >= (now() AT TIME ZONE c.timezone)::date)""";

    /**
     * @param effective TRIAL, ACTIVE, FREE, EXPIRED (prazo acabou), SUSPENDED ou BLOCKED
     * @param daysLeft  dias até o fim do prazo (0 = acaba hoje; negativo = vencido); null sem prazo
     */
    public record Access(String status, String effective, LocalDate accessUntil, Integer daysLeft, LocalDate today,
                         String reason) {
        public boolean blocked() {
            return "BLOCKED".equals(effective);
        }

        public boolean readOnly() {
            return "SUSPENDED".equals(effective) || "EXPIRED".equals(effective);
        }
    }

    private final JdbcTemplate jdbc;

    public ClinicAccess(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Access of(UUID clinicId) {
        return jdbc.query("SELECT status, access_until, timezone, status_reason FROM clinics WHERE id = ?",
                (rs, n) -> compute(rs.getString("status"), rs.getObject("access_until", LocalDate.class),
                    LocalDate.now(ZoneId.of(rs.getString("timezone"))), rs.getString("status_reason")), clinicId)
            .stream().findFirst().orElseThrow(NoSuchElementException::new);
    }

    public static Access compute(String status, LocalDate until, LocalDate today, String reason) {
        Integer daysLeft = until == null ? null : (int) ChronoUnit.DAYS.between(today, until);
        String effective = status;
        if (("TRIAL".equals(status) || "ACTIVE".equals(status) || "FREE".equals(status)) && daysLeft != null && daysLeft < 0) {
            effective = "EXPIRED";
        }
        return new Access(status, effective, until, daysLeft, today, reason);
    }
}
