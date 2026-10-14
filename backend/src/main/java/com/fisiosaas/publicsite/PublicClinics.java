package com.fisiosaas.publicsite;

import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Resolve a clínica pelo endereço público. Página desativada ou slug inexistente: 404, sem diferença. */
@Component
public class PublicClinics {
    private final JdbcTemplate jdbc;

    public PublicClinics(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID requireEnabled(String slug) {
        return jdbc.query("SELECT id FROM clinics WHERE slug = ? AND public_page_enabled",
                (rs, n) -> rs.getObject("id", UUID.class), slug.toLowerCase(Locale.ROOT))
            .stream().findFirst().orElseThrow(NoSuchElementException::new);
    }
}
