package com.fisiosaas.publicsite;

import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;
import com.fisiosaas.platform.ClinicAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Resolve a clínica pelo endereço público. Página desativada, clínica bloqueada ou slug inexistente: 404, sem diferença. */
@Component
public class PublicClinics {
    private final JdbcTemplate jdbc;

    public PublicClinics(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID requireEnabled(String slug) {
        return jdbc.query("SELECT id FROM clinics WHERE slug = ? AND public_page_enabled AND status <> 'BLOCKED'",
                (rs, n) -> rs.getObject("id", UUID.class), slug.toLowerCase(Locale.ROOT))
            .stream().findFirst().orElseThrow(NoSuchElementException::new);
    }

    /** Clínica em dia (teste, assinante ou acesso gratuito no prazo): pode receber agendamentos pelo site. */
    public boolean operational(UUID clinicId) {
        Boolean ok = jdbc.queryForObject("SELECT " + ClinicAccess.OPERATIONAL_SQL + " FROM clinics c WHERE c.id = ?",
            Boolean.class, clinicId);
        return Boolean.TRUE.equals(ok);
    }
}
