package com.fisiosaas.platform;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Administração da plataforma (equipe PhysioEvo): situação de cada clínica assinante. Mostra só dados da conta
 * (nome, responsável, contato, uso); nunca dados de pacientes. Toda ação exige motivo e fica no histórico.
 * Acesso restrito ao papel PLATFORM_ADMIN (SecurityConfig).
 */
@RestController
@RequestMapping("/api/v1/admin")
public class PlatformAdminController {

    public record ClinicRow(UUID id, String slug, String displayName, String ownerEmail, String ownerName,
                            String whatsapp, OffsetDateTime createdAt, String status, String effectiveStatus,
                            LocalDate accessUntil, Integer daysLeft, String statusReason, boolean publicPageEnabled,
                            int users, int patients) {}

    public record LogEntry(String adminEmail, String action, String statusBefore, String statusAfter,
                           LocalDate accessUntil, String reason, OffsetDateTime createdAt) {}

    public record ClinicDetail(ClinicRow clinic, List<LogEntry> history) {}

    public record Overview(LocalDate today, Map<String, Integer> counts, int expiringSoon, List<ClinicRow> clinics) {}

    /**
     * action: TRIAL (teste com prazo), FREE (acesso gratuito; sem prazo se days e until vierem vazios),
     * ACTIVE (assinatura; prazo opcional), SUSPEND (só consulta) ou BLOCK (sem acesso).
     */
    public record AccessRequest(@NotBlank String action, Integer days, LocalDate until,
                                @NotBlank(message = "Informe o motivo") @Size(min = 3, max = 300) String reason) {}

    private final JdbcTemplate jdbc;
    private final ClinicAccess access;

    public PlatformAdminController(JdbcTemplate jdbc, ClinicAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    private static final String SELECT = """
        SELECT c.id, c.slug, c.display_name, c.whatsapp, c.created_at, c.status, c.access_until, c.status_reason,
               c.public_page_enabled, (now() AT TIME ZONE c.timezone)::date AS today,
               (SELECT u.email FROM users u WHERE u.clinic_id = c.id AND u.role = 'OWNER' AND u.active
                 ORDER BY u.created_at LIMIT 1) AS owner_email,
               (SELECT u.full_name FROM users u WHERE u.clinic_id = c.id AND u.role = 'OWNER' AND u.active
                 ORDER BY u.created_at LIMIT 1) AS owner_name,
               (SELECT count(*) FROM users u WHERE u.clinic_id = c.id AND u.active) AS users,
               (SELECT count(*) FROM patients p WHERE p.clinic_id = c.id) AS patients
          FROM clinics c""";

    private static ClinicRow map(java.sql.ResultSet rs) throws java.sql.SQLException {
        ClinicAccess.Access state = ClinicAccess.compute(rs.getString("status"), rs.getObject("access_until", LocalDate.class),
            rs.getObject("today", LocalDate.class), rs.getString("status_reason"));
        return new ClinicRow(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("display_name"),
            rs.getString("owner_email"), rs.getString("owner_name"), rs.getString("whatsapp"),
            rs.getObject("created_at", OffsetDateTime.class), state.status(), state.effective(), state.accessUntil(),
            state.daysLeft(), state.reason(), rs.getBoolean("public_page_enabled"), rs.getInt("users"), rs.getInt("patients"));
    }

    /** Todas as clínicas (busca por nome, endereço ou e-mail da responsável) e os totais por situação. */
    @GetMapping("/clinics")
    public Overview clinics(@RequestParam(required = false) String q) {
        String term = q == null ? "" : q.trim().toLowerCase(java.util.Locale.ROOT);
        List<ClinicRow> all = jdbc.query(SELECT + " ORDER BY c.created_at DESC", (rs, n) -> map(rs));
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String s : List.of("TRIAL", "ACTIVE", "FREE", "EXPIRED", "SUSPENDED", "BLOCKED")) counts.put(s, 0);
        int expiring = 0;
        for (ClinicRow row : all) {
            counts.merge(row.effectiveStatus(), 1, Integer::sum);
            if (row.daysLeft() != null && row.daysLeft() >= 0 && row.daysLeft() <= ClinicAccess.WARN_DAYS
                && !"SUSPENDED".equals(row.status()) && !"BLOCKED".equals(row.status())) expiring++;
        }
        List<ClinicRow> filtered = term.isEmpty() ? all : all.stream().filter(r ->
            r.displayName().toLowerCase(java.util.Locale.ROOT).contains(term) || r.slug().contains(term)
                || (r.ownerEmail() != null && r.ownerEmail().toLowerCase(java.util.Locale.ROOT).contains(term))).toList();
        return new Overview(LocalDate.now(java.time.ZoneId.of("America/Fortaleza")), counts, expiring, filtered);
    }

    @GetMapping("/clinics/{id}")
    public ClinicDetail clinic(@PathVariable UUID id) {
        ClinicRow row = jdbc.query(SELECT + " WHERE c.id = ?", (rs, n) -> map(rs), id).stream().findFirst()
            .orElseThrow(NoSuchElementException::new);
        List<LogEntry> history = jdbc.query("""
                SELECT * FROM clinic_access_log WHERE clinic_id = ? ORDER BY created_at DESC LIMIT 100""",
            (rs, n) -> new LogEntry(rs.getString("admin_email"), rs.getString("action"), rs.getString("status_before"),
                rs.getString("status_after"), rs.getObject("access_until", LocalDate.class), rs.getString("reason"),
                rs.getObject("created_at", OffsetDateTime.class)), id);
        return new ClinicDetail(row, history);
    }

    /** Muda a situação da clínica. Os dados nunca são apagados; bloquear também derruba os logins abertos. */
    @PostMapping("/clinics/{id}/access")
    @Transactional
    public ClinicDetail changeAccess(Authentication auth, @PathVariable UUID id, @Valid @RequestBody AccessRequest body) {
        ClinicAccess.Access before = access.of(id);
        LocalDate today = before.today();
        String action = body.action().trim().toUpperCase(java.util.Locale.ROOT);
        LocalDate until = until(body, today);
        String status;
        LocalDate newUntil;
        switch (action) {
            case "TRIAL" -> {
                if (until == null) throw new IllegalArgumentException("Informe por quantos dias vai o teste");
                status = "TRIAL";
                newUntil = until;
            }
            case "FREE" -> { status = "FREE"; newUntil = until; }
            case "ACTIVE" -> { status = "ACTIVE"; newUntil = until; }
            case "SUSPEND" -> { status = "SUSPENDED"; newUntil = before.accessUntil(); }
            case "BLOCK" -> { status = "BLOCKED"; newUntil = before.accessUntil(); }
            default -> throw new IllegalArgumentException("Ação inválida");
        }
        jdbc.update("""
            UPDATE clinics SET status = ?, access_until = ?, status_reason = ?, status_changed_at = now() WHERE id = ?""",
            status, newUntil, body.reason().trim(), id);
        jdbc.update("""
            INSERT INTO clinic_access_log(id, clinic_id, admin_email, action, status_before, status_after, access_until, reason)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            UUID.randomUUID(), id, auth.getName(), action, before.effective(), status, newUntil, body.reason().trim());
        if ("BLOCKED".equals(status)) {
            jdbc.update("""
                DELETE FROM spring_session WHERE lower(principal_name) IN
                  (SELECT lower(email) FROM users WHERE clinic_id = ?)""", id);
        }
        return clinic(id);
    }

    /** Prazo pedido: uma data (até) ou uma quantidade de dias a partir de hoje. Vazio = sem prazo. */
    static LocalDate until(AccessRequest body, LocalDate today) {
        if (body.until() != null) {
            if (body.until().isBefore(today)) throw new IllegalArgumentException("A data final não pode ser no passado");
            return body.until();
        }
        if (body.days() != null) {
            if (body.days() < 1 || body.days() > 3650) throw new IllegalArgumentException("Informe de 1 a 3650 dias");
            return today.plusDays(body.days());
        }
        return null;
    }
}
