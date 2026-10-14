package com.fisiosaas.clinic;

import com.fisiosaas.identity.CurrentClinic;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Depoimentos de pacientes para o site. Só entram com a autorização do paciente confirmada pela clínica, e só
 * aparecem no site se a clínica ligar a seção (clinics.site_show_testimonials). Remover = desativar.
 */
@RestController
@RequestMapping("/api/v1/clinic/testimonials")
public class TestimonialController {
    static final int MAX_ACTIVE = 20;
    private static final String TABLE = "clinic_testimonials";
    private static final String ACTIVE = "active";

    public record TestimonialRequest(@NotBlank @Size(max = 60) String authorName,
                                     @NotBlank @Size(max = 500) String text,
                                     Boolean consent) {}

    public record TestimonialView(UUID id, String authorName, String text, int position,
                                  OffsetDateTime consentConfirmedAt) {}

    static final RowMapper<TestimonialView> MAPPER = (rs, n) -> new TestimonialView(rs.getObject("id", UUID.class),
        rs.getString("author_name"), rs.getString("text"), rs.getInt("position"),
        rs.getObject("consent_confirmed_at", OffsetDateTime.class));

    private final JdbcTemplate jdbc;
    private final CurrentClinic current;

    public TestimonialController(JdbcTemplate jdbc, CurrentClinic current) {
        this.jdbc = jdbc;
        this.current = current;
    }

    @GetMapping
    public List<TestimonialView> list(Authentication auth) {
        return list(jdbc, current.require(auth).clinicId());
    }

    public static List<TestimonialView> list(JdbcTemplate jdbc, UUID clinicId) {
        return jdbc.query("SELECT * FROM clinic_testimonials WHERE clinic_id = ? AND active ORDER BY position, created_at, id",
            MAPPER, clinicId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public TestimonialView create(Authentication auth, @Valid @RequestBody TestimonialRequest body) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        if (!Boolean.TRUE.equals(body.consent())) {
            throw new IllegalArgumentException("Confirme que o paciente autorizou por escrito a publicação do depoimento");
        }
        jdbc.queryForObject("SELECT id FROM clinics WHERE id = ? FOR UPDATE", UUID.class, clinicId);
        Integer count = jdbc.queryForObject("SELECT count(*) FROM clinic_testimonials WHERE clinic_id = ? AND active",
            Integer.class, clinicId);
        if (count >= MAX_ACTIVE) throw new IllegalArgumentException("Limite de " + MAX_ACTIVE + " depoimentos");
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO clinic_testimonials(id, clinic_id, author_name, text, position, consent_confirmed_at)
            VALUES (?, ?, ?, ?, ?, now())""",
            id, clinicId, body.authorName().trim(), body.text().trim(), Positions.next(jdbc, TABLE, ACTIVE, clinicId));
        return find(clinicId, id);
    }

    @PutMapping("/{id}")
    public TestimonialView update(Authentication auth, @PathVariable UUID id, @Valid @RequestBody TestimonialRequest body) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        find(clinicId, id);
        jdbc.update("""
            UPDATE clinic_testimonials SET author_name = ?, text = ?, updated_at = now()
             WHERE id = ? AND clinic_id = ? AND active""", body.authorName().trim(), body.text().trim(), id, clinicId);
        return find(clinicId, id);
    }

    @PostMapping("/{id}/move")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void move(Authentication auth, @PathVariable UUID id, @Valid @RequestBody PhotoController.Move body) {
        Positions.move(jdbc, TABLE, ACTIVE, current.requireOwner(auth).clinicId(), id, body.direction());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(Authentication auth, @PathVariable UUID id) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        find(clinicId, id);
        jdbc.update("UPDATE clinic_testimonials SET active = FALSE, updated_at = now() WHERE id = ? AND clinic_id = ?",
            id, clinicId);
    }

    /** Só ativos: desativado se comporta como inexistente (404). */
    private TestimonialView find(UUID clinicId, UUID id) {
        return jdbc.query("SELECT * FROM clinic_testimonials WHERE id = ? AND clinic_id = ? AND active", MAPPER, id, clinicId)
            .stream().findFirst().orElseThrow(NoSuchElementException::new);
    }
}
