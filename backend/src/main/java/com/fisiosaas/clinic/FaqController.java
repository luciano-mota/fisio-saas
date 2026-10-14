package com.fisiosaas.clinic;

import com.fisiosaas.identity.CurrentClinic;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Perguntas frequentes do site da clínica. Remover = desativar. */
@RestController
@RequestMapping("/api/v1/clinic/faqs")
public class FaqController {
    static final int MAX_ACTIVE = 20;
    private static final String TABLE = "clinic_faqs";
    private static final String ACTIVE = "active";

    public record FaqRequest(@NotBlank @Size(max = 200) String question, @NotBlank @Size(max = 800) String answer) {}

    public record FaqView(UUID id, String question, String answer, int position) {}

    static final RowMapper<FaqView> MAPPER = (rs, n) -> new FaqView(rs.getObject("id", UUID.class),
        rs.getString("question"), rs.getString("answer"), rs.getInt("position"));

    private final JdbcTemplate jdbc;
    private final CurrentClinic current;

    public FaqController(JdbcTemplate jdbc, CurrentClinic current) {
        this.jdbc = jdbc;
        this.current = current;
    }

    @GetMapping
    public List<FaqView> list(Authentication auth) {
        return list(jdbc, current.require(auth).clinicId());
    }

    public static List<FaqView> list(JdbcTemplate jdbc, UUID clinicId) {
        return jdbc.query("SELECT * FROM clinic_faqs WHERE clinic_id = ? AND active ORDER BY position, created_at, id",
            MAPPER, clinicId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public FaqView create(Authentication auth, @Valid @RequestBody FaqRequest body) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        jdbc.queryForObject("SELECT id FROM clinics WHERE id = ? FOR UPDATE", UUID.class, clinicId);
        Integer count = jdbc.queryForObject("SELECT count(*) FROM clinic_faqs WHERE clinic_id = ? AND active",
            Integer.class, clinicId);
        if (count >= MAX_ACTIVE) throw new IllegalArgumentException("Limite de " + MAX_ACTIVE + " perguntas");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO clinic_faqs(id, clinic_id, question, answer, position) VALUES (?, ?, ?, ?, ?)",
            id, clinicId, body.question().trim(), body.answer().trim(), Positions.next(jdbc, TABLE, ACTIVE, clinicId));
        return find(clinicId, id);
    }

    @PutMapping("/{id}")
    public FaqView update(Authentication auth, @PathVariable UUID id, @Valid @RequestBody FaqRequest body) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        find(clinicId, id);
        jdbc.update("""
            UPDATE clinic_faqs SET question = ?, answer = ?, updated_at = now()
             WHERE id = ? AND clinic_id = ? AND active""", body.question().trim(), body.answer().trim(), id, clinicId);
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
        jdbc.update("UPDATE clinic_faqs SET active = FALSE, updated_at = now() WHERE id = ? AND clinic_id = ?", id, clinicId);
    }

    private FaqView find(UUID clinicId, UUID id) {
        return jdbc.query("SELECT * FROM clinic_faqs WHERE id = ? AND clinic_id = ? AND active", MAPPER, id, clinicId)
            .stream().findFirst().orElseThrow(NoSuchElementException::new);
    }
}
