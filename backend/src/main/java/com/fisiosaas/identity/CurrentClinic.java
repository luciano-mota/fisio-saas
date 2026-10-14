package com.fisiosaas.identity;

import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Resolve a clínica (tenant) do usuário logado. TODA consulta de dados de clínica
 * deve filtrar pelo clinicId devolvido aqui; nunca confiar em clinicId vindo do cliente.
 */
@Component
public class CurrentClinic {

    public record ClinicUser(UUID userId, UUID clinicId, String email, String role) {
        public boolean isOwner() {
            return "OWNER".equals(role);
        }
    }

    private final JdbcTemplate jdbc;

    public CurrentClinic(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ClinicUser require(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) throw new AccessDeniedException("Não autenticado");
        ClinicUser user = jdbc.query(
            "SELECT id, clinic_id, email, role FROM users WHERE lower(email) = ? AND active",
            rs -> rs.next()
                ? new ClinicUser(rs.getObject("id", UUID.class), rs.getObject("clinic_id", UUID.class),
                    rs.getString("email"), rs.getString("role"))
                : null,
            auth.getName().trim().toLowerCase(Locale.ROOT));
        if (user == null) throw new AccessDeniedException("Usuário inativo");
        return user;
    }

    public ClinicUser requireOwner(Authentication auth) {
        ClinicUser user = require(auth);
        if (!user.isOwner()) throw new AccessDeniedException("Somente o responsável da clínica");
        return user;
    }
}
