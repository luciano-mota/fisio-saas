package com.fisiosaas.identity;

import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cria a PRIMEIRA clínica e sua responsável quando o banco está vazio (ex.: piloto).
 * Sem cadastro público e sem senha padrão. Remova as variáveis BOOTSTRAP_* depois do
 * primeiro login.
 */
@Component
public class BootstrapClinic implements CommandLineRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;

    public BootstrapClinic(JdbcTemplate jdbc, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.passwords = passwords;
    }

    private static String env(String key) {
        String value = System.getenv(key);
        return value == null ? "" : value.trim();
    }

    @Override
    @Transactional
    public void run(String... args) {
        String email = env("BOOTSTRAP_OWNER_EMAIL").toLowerCase(Locale.ROOT);
        String password = env("BOOTSTRAP_OWNER_PASSWORD");
        if (email.isEmpty() && password.isEmpty()) return;

        Integer clinics = jdbc.queryForObject("SELECT count(*) FROM clinics", Integer.class);
        if (clinics != null && clinics > 0) return;

        String name = env("BOOTSTRAP_CLINIC_NAME");
        String slug = env("BOOTSTRAP_CLINIC_SLUG").toLowerCase(Locale.ROOT);
        if (!email.contains("@") || email.length() > 255 || password.length() < 12
                || name.isEmpty() || !slug.matches("^[a-z0-9]([a-z0-9-]{1,61}[a-z0-9])?$")) {
            throw new IllegalStateException(
                "Bootstrap exige BOOTSTRAP_OWNER_EMAIL válido, BOOTSTRAP_OWNER_PASSWORD com 12+ caracteres, "
                    + "BOOTSTRAP_CLINIC_NAME e BOOTSTRAP_CLINIC_SLUG (minúsculas, números e hífen)");
        }

        UUID clinic = UUID.randomUUID();
        jdbc.update("INSERT INTO clinics(id, slug, display_name) VALUES (?, ?, ?)", clinic, slug, name);
        jdbc.update("INSERT INTO users(id, clinic_id, email, password_hash, role) VALUES (?, ?, ?, ?, 'OWNER')",
            UUID.randomUUID(), clinic, email, passwords.encode(password));
    }
}
