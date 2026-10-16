package com.fisiosaas.platform;

import com.fisiosaas.identity.CurrentClinic;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Avisos da plataforma para as clínicas. A equipe (PLATFORM_ADMIN) cria em /api/v1/admin/notices; cada clínica vê em
 * /api/v1/notices só os avisos para todas ou para ela, dentro do período, e marca como lido.
 */
@RestController
public class NoticeController {
    static final String ADMIN = "/api/v1/admin/notices";

    public record NoticeRequest(@NotBlank(message = "Informe o título") @Size(max = 120) String title,
                                @NotBlank(message = "Escreva a mensagem") @Size(max = 2000) String body,
                                @NotBlank String level, @NotBlank String display, UUID clinicId,
                                LocalDate startsOn, LocalDate endsOn, Boolean active) {}

    /** Visão da equipe: com destino, situação e leituras. status: SCHEDULED, LIVE, ENDED ou INACTIVE. */
    public record AdminNotice(UUID id, String title, String body, String level, String display, UUID clinicId,
                              String clinicName, LocalDate startsOn, LocalDate endsOn, boolean active, String status,
                              int readers, int clinicsRead, String createdBy, OffsetDateTime createdAt) {}

    /** Visão da clínica. */
    public record ClinicNotice(UUID id, String title, String body, String level, String display, LocalDate startsOn,
                               boolean read) {}

    private final JdbcTemplate jdbc;
    private final CurrentClinic current;

    public NoticeController(JdbcTemplate jdbc, CurrentClinic current) {
        this.jdbc = jdbc;
        this.current = current;
    }

    static LocalDate today() {
        return LocalDate.now(ZoneId.of("America/Fortaleza"));
    }

    // ---------- Equipe da plataforma ----------

    @GetMapping(ADMIN)
    public List<AdminNotice> adminList() {
        return jdbc.query("""
                SELECT n.*, c.display_name AS clinic_name,
                       (SELECT count(*) FROM platform_notice_reads r WHERE r.notice_id = n.id) AS readers,
                       (SELECT count(DISTINCT u.clinic_id) FROM platform_notice_reads r JOIN users u ON u.id = r.user_id
                         WHERE r.notice_id = n.id) AS clinics_read
                  FROM platform_notices n LEFT JOIN clinics c ON c.id = n.clinic_id
                 ORDER BY n.active DESC, n.starts_on DESC, n.created_at DESC""",
            (rs, n) -> {
                LocalDate starts = rs.getObject("starts_on", LocalDate.class);
                LocalDate ends = rs.getObject("ends_on", LocalDate.class);
                boolean active = rs.getBoolean("active");
                LocalDate today = today();
                String status = !active ? "INACTIVE" : starts.isAfter(today) ? "SCHEDULED"
                    : ends != null && ends.isBefore(today) ? "ENDED" : "LIVE";
                return new AdminNotice(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("body"),
                    rs.getString("level"), rs.getString("display"), rs.getObject("clinic_id", UUID.class),
                    rs.getString("clinic_name"), starts, ends, active, status, rs.getInt("readers"), rs.getInt("clinics_read"),
                    rs.getString("created_by"), rs.getObject("created_at", OffsetDateTime.class));
            });
    }

    @PostMapping(ADMIN)
    @ResponseStatus(HttpStatus.CREATED)
    public AdminNotice create(Authentication auth, @Valid @RequestBody NoticeRequest body) {
        Clean c = clean(body);
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO platform_notices(id, title, body, level, display, clinic_id, starts_on, ends_on, active, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            id, c.title(), c.body(), c.level(), c.display(), body.clinicId(), c.startsOn(), body.endsOn(),
            !Boolean.FALSE.equals(body.active()), auth.getName());
        return find(id);
    }

    @PutMapping(ADMIN + "/{id}")
    public AdminNotice update(@PathVariable UUID id, @Valid @RequestBody NoticeRequest body) {
        AdminNotice old = find(id);
        Clean c = clean(body);
        jdbc.update("""
            UPDATE platform_notices SET title = ?, body = ?, level = ?, display = ?, clinic_id = ?, starts_on = ?, ends_on = ?,
                   active = ?, updated_at = now() WHERE id = ?""",
            c.title(), c.body(), c.level(), c.display(), body.clinicId(), c.startsOn(), body.endsOn(),
            body.active() == null ? old.active() : body.active(), id);
        return find(id);
    }

    /** Não apaga: desativa (sai da tela das clínicas e fica no histórico). */
    @DeleteMapping(ADMIN + "/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable UUID id) {
        find(id);
        jdbc.update("UPDATE platform_notices SET active = FALSE, updated_at = now() WHERE id = ?", id);
    }

    private AdminNotice find(UUID id) {
        return adminList().stream().filter(n -> n.id().equals(id)).findFirst().orElseThrow(NoSuchElementException::new);
    }

    private record Clean(String title, String body, String level, String display, LocalDate startsOn) {}

    private Clean clean(NoticeRequest body) {
        String level = body.level().trim().toUpperCase(java.util.Locale.ROOT);
        String display = body.display().trim().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("INFO", "IMPORTANT", "URGENT").contains(level)) throw new IllegalArgumentException("Nível inválido");
        if (!List.of("BANNER", "MODAL").contains(display)) throw new IllegalArgumentException("Forma de exibição inválida");
        if (body.clinicId() != null) {
            Integer exists = jdbc.queryForObject("SELECT count(*) FROM clinics WHERE id = ?", Integer.class, body.clinicId());
            if (exists == null || exists == 0) throw new NoSuchElementException();
        }
        LocalDate starts = body.startsOn() == null ? today() : body.startsOn();
        if (body.endsOn() != null && body.endsOn().isBefore(starts)) {
            throw new IllegalArgumentException("A data de saída deve ser depois do início");
        }
        return new Clean(body.title().trim(), body.body().strip(), level, display, starts);
    }

    // ---------- Clínica ----------

    /** Avisos no ar para a clínica de quem está logado (todas ou só ela), urgentes primeiro. */
    @GetMapping("/api/v1/notices")
    public List<ClinicNotice> mine(Authentication auth) {
        var user = current.require(auth);
        return jdbc.query("""
                SELECT n.*, EXISTS (SELECT 1 FROM platform_notice_reads r WHERE r.notice_id = n.id AND r.user_id = ?) AS is_read
                  FROM platform_notices n JOIN clinics c ON c.id = ?
                 WHERE n.active AND (n.clinic_id IS NULL OR n.clinic_id = c.id)
                   AND n.starts_on <= (now() AT TIME ZONE c.timezone)::date
                   AND (n.ends_on IS NULL OR n.ends_on >= (now() AT TIME ZONE c.timezone)::date)
                 ORDER BY CASE n.level WHEN 'URGENT' THEN 0 WHEN 'IMPORTANT' THEN 1 ELSE 2 END, n.starts_on DESC, n.created_at DESC""",
            (rs, n) -> new ClinicNotice(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("body"),
                rs.getString("level"), rs.getString("display"), rs.getObject("starts_on", LocalDate.class), rs.getBoolean("is_read")),
            user.userId(), user.clinicId());
    }

    /** Marca como lido (some da tela de quem leu). Aviso de outra clínica: 404. */
    @PostMapping("/api/v1/notices/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(Authentication auth, @PathVariable UUID id) {
        var user = current.require(auth);
        boolean visible = mine(auth).stream().anyMatch(n -> n.id().equals(id));
        if (!visible) throw new NoSuchElementException();
        jdbc.update("""
            INSERT INTO platform_notice_reads(notice_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING""", id, user.userId());
    }
}
