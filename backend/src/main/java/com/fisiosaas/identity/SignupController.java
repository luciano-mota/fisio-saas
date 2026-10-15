package com.fisiosaas.identity;

import com.fisiosaas.api.ConflictException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Cadastro de uma nova clínica pelo site (período de teste). Cria a clínica e a responsável; o painel faz o login em
 * seguida. Exige aceite dos termos e da política de privacidade (versão guardada). Protegido por campo anti-robô e
 * limite por IP. Pode ser desligado com SIGNUP_ENABLED=false.
 */
@RestController
@RequestMapping("/api/v1/public")
public class SignupController {
    private static final Logger log = LoggerFactory.getLogger(SignupController.class);
    /** Muda quando o texto dos termos ou da política mudar. */
    public static final String TERMS_VERSION = "2026-10-15";
    /** Fuso padrão de clínica nova (o mesmo default da coluna clinics.timezone). */
    static final String DEFAULT_TIMEZONE = "America/Fortaleza";

    public record SignupRequest(
        @NotBlank @Size(max = 120) String clinicName,
        @Size(max = 120) String professionalName,
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 128) String password,
        @Size(max = 20) String whatsapp,
        Boolean acceptTerms,
        /** Campo escondido: só robôs preenchem. */
        String website) {}

    public record Created(String slug) {}

    public record Platform(boolean signupEnabled, String contactEmail, String termsVersion) {}

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final SignupThrottle throttle;
    private final boolean enabled;
    private final String contactEmail;
    private final int trialDays;

    public SignupController(JdbcTemplate jdbc, PasswordEncoder passwords, SignupThrottle throttle,
                            @Value("${fisio.signup-enabled:true}") boolean enabled,
                            @Value("${fisio.contact-email:}") String contactEmail,
                            @Value("${fisio.trial-days:30}") int trialDays) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.throttle = throttle;
        this.enabled = enabled;
        this.contactEmail = contactEmail == null ? "" : contactEmail.trim();
        this.trialDays = trialDays;
    }

    @GetMapping("/platform")
    public Platform platform() {
        return new Platform(enabled, contactEmail.isEmpty() ? null : contactEmail, TERMS_VERSION);
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Created signup(@Valid @RequestBody SignupRequest body, HttpServletRequest http) {
        if (!enabled) throw new NoSuchElementException();
        if (body.website() != null && !body.website().isBlank()) throw new IllegalArgumentException("Não foi possível criar a conta");
        if (!throttle.allow(http.getRemoteAddr())) {
            throw new IllegalArgumentException("Muitas tentativas seguidas. Tente de novo mais tarde.");
        }
        if (!Boolean.TRUE.equals(body.acceptTerms())) {
            throw new IllegalArgumentException("Para criar a conta, aceite os termos de uso e a política de privacidade");
        }
        String email = body.email().trim().toLowerCase(Locale.ROOT);
        AccountController.validatePassword(body.password(), email);
        Integer taken = jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM users WHERE lower(email) = ?)
                     + (SELECT count(*) FROM platform_admins WHERE lower(email) = ?)""",
            Integer.class, email, email);
        if (taken != null && taken > 0) throw new ConflictException("Este e-mail já tem conta. Entre com ele ou use outro e-mail.");
        String whatsapp = body.whatsapp() == null ? null : body.whatsapp().replaceAll("\\D", "");
        if (whatsapp != null && whatsapp.isEmpty()) whatsapp = null;
        if (whatsapp != null && (whatsapp.length() < 10 || whatsapp.length() > 13)) {
            throw new IllegalArgumentException("WhatsApp inválido: informe DDD e número");
        }

        String slug = uniqueSlug(slugify(body.clinicName()));
        UUID clinic = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO clinics(id, slug, display_name, professional_name, whatsapp, terms_accepted_at, terms_version,
                                status, access_until, status_changed_at)
            VALUES (?, ?, ?, ?, ?, now(), ?, 'TRIAL', ?, now())""",
            clinic, slug, body.clinicName().trim(), blankToNull(body.professionalName()), whatsapp, TERMS_VERSION,
            LocalDate.now(ZoneId.of(DEFAULT_TIMEZONE)).plusDays(trialDays));
        jdbc.update("""
            INSERT INTO users(id, clinic_id, email, password_hash, full_name, role, password_changed_at)
            VALUES (?, ?, ?, ?, ?, 'OWNER', now())""",
            UUID.randomUUID(), clinic, email, passwords.encode(body.password()), blankToNull(body.professionalName()));
        log.info("Nova clínica cadastrada pelo site: clinica={} slug={}", clinic, slug);
        return new Created(slug);
    }

    /** "Clínica Movimento & Saúde" → "clinica-movimento-saude". */
    static String slugify(String name) {
        String plain = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (plain.length() > 50) plain = plain.substring(0, 50).replaceAll("-+$", "");
        return plain.length() < 3 ? "clinica" : plain;
    }

    private String uniqueSlug(String base) {
        String slug = base;
        for (int n = 2; ; n++) {
            Integer used = jdbc.queryForObject("SELECT count(*) FROM clinics WHERE slug = ?", Integer.class, slug);
            if (used == null || used == 0) return slug;
            slug = base + "-" + n;
        }
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    /** No máximo 3 cadastros por IP por hora (em memória). */
    @Component
    public static class SignupThrottle {
        static final int MAX = 3;
        private final ConcurrentHashMap<String, Deque<Instant>> hits = new ConcurrentHashMap<>();
        private final Clock clock = Clock.systemUTC();

        public boolean allow(String key) {
            Instant now = clock.instant();
            if (hits.size() > 10_000) hits.clear();
            Deque<Instant> list = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
            synchronized (list) {
                while (!list.isEmpty() && list.peekFirst().isBefore(now.minus(Duration.ofHours(1)))) list.pollFirst();
                if (list.size() >= MAX) return false;
                list.addLast(now);
                return true;
            }
        }

        public void reset() {
            hits.clear();
        }
    }
}
