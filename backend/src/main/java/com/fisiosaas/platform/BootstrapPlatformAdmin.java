package com.fisiosaas.platform;

import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Cria o administrador da plataforma (PLATFORM_ADMIN_EMAIL / PLATFORM_ADMIN_PASSWORD) se ele ainda não existe.
 * Não troca a senha de quem já existe. O e-mail não pode ser de alguém de uma clínica.
 */
@Component
public class BootstrapPlatformAdmin implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(BootstrapPlatformAdmin.class);

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;

    public BootstrapPlatformAdmin(JdbcTemplate jdbc, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.passwords = passwords;
    }

    private static String env(String key) {
        String value = System.getenv(key);
        return value == null ? "" : value.trim();
    }

    @Override
    public void run(String... args) {
        String email = env("PLATFORM_ADMIN_EMAIL").toLowerCase(Locale.ROOT);
        String password = env("PLATFORM_ADMIN_PASSWORD");
        if (email.isEmpty() && password.isEmpty()) return;
        if (!email.contains("@") || email.length() > 255 || password.length() < 12) {
            throw new IllegalStateException("PLATFORM_ADMIN_EMAIL precisa ser válido e PLATFORM_ADMIN_PASSWORD ter 12+ caracteres");
        }
        Integer clinicUser = jdbc.queryForObject("SELECT count(*) FROM users WHERE lower(email) = ?", Integer.class, email);
        if (clinicUser != null && clinicUser > 0) {
            throw new IllegalStateException("PLATFORM_ADMIN_EMAIL já é usado por alguém de uma clínica; use outro e-mail");
        }
        int created = jdbc.update("""
            INSERT INTO platform_admins(id, email, password_hash) VALUES (?, ?, ?)
            ON CONFLICT ((lower(email))) DO NOTHING""", UUID.randomUUID(), email, passwords.encode(password));
        if (created > 0) log.info("Administrador da plataforma criado: {}", email);
    }
}
