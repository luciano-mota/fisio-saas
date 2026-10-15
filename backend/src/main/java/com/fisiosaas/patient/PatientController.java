package com.fisiosaas.patient;

import com.fisiosaas.api.ConflictException;
import com.fisiosaas.identity.CurrentClinic;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Cadastro de pacientes: só dados administrativos (sem dados clínicos nesta fase). */
@RestController
@RequestMapping("/api/v1/patients")
public class PatientController {
    static final int MAX_RESULTS = 200;

    public record PatientRequest(
        @NotBlank @Size(max = 120) String fullName,
        @Size(max = 20) String cpf,
        @Size(max = 25) String whatsapp,
        @PastOrPresent(message = "não pode ser no futuro") LocalDate birthDate,
        @Size(max = 1000) String adminNotes,
        Boolean active) {}

    public record PatientView(UUID id, String fullName, String cpf, String whatsapp, LocalDate birthDate,
                              String adminNotes, boolean active) {}

    static final RowMapper<PatientView> MAPPER = (rs, row) -> new PatientView(
        rs.getObject("id", UUID.class), rs.getString("full_name"), rs.getString("cpf"), rs.getString("whatsapp"),
        rs.getObject("birth_date", LocalDate.class), rs.getString("admin_notes"), rs.getBoolean("active"));

    private final JdbcTemplate jdbc;
    private final CurrentClinic current;

    public PatientController(JdbcTemplate jdbc, CurrentClinic current) {
        this.jdbc = jdbc;
        this.current = current;
    }

    /** Busca por parte do nome, sem diferenciar maiúsculas nem acentos. */
    @GetMapping
    public List<PatientView> list(Authentication auth, @RequestParam(required = false) String q,
                                  @RequestParam(defaultValue = "false") boolean includeInactive) {
        UUID clinicId = current.require(auth).clinicId();
        String pattern = "%" + escapeLike(searchKey(q == null ? "" : q)) + "%";
        return jdbc.query("""
                SELECT * FROM patients
                 WHERE clinic_id = ? AND (active OR ?) AND search_name LIKE ? ESCAPE '\\'
                 ORDER BY active DESC, search_name LIMIT ?""",
            MAPPER, clinicId, includeInactive, pattern, MAX_RESULTS);
    }

    @GetMapping("/{id}")
    public PatientView get(Authentication auth, @PathVariable UUID id) {
        return find(current.require(auth).clinicId(), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PatientView create(Authentication auth, @Valid @RequestBody PatientRequest body) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        Clean data = clean(body);
        requireUniqueCpf(clinicId, data.cpf(), null);
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO patients(id, clinic_id, full_name, search_name, cpf, whatsapp, birth_date, admin_notes, active)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            id, clinicId, data.fullName(), searchKey(data.fullName()), data.cpf(), data.whatsapp(),
            body.birthDate(), blankToNull(body.adminNotes()), !Boolean.FALSE.equals(body.active()));
        return find(clinicId, id);
    }

    @PutMapping("/{id}")
    public PatientView update(Authentication auth, @PathVariable UUID id, @Valid @RequestBody PatientRequest body) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        PatientView old = find(clinicId, id);
        Clean data = clean(body);
        requireUniqueCpf(clinicId, data.cpf(), id);
        jdbc.update("""
            UPDATE patients SET full_name = ?, search_name = ?, cpf = ?, whatsapp = ?, birth_date = ?,
                   admin_notes = ?, active = ?, updated_at = now()
             WHERE id = ? AND clinic_id = ?""",
            data.fullName(), searchKey(data.fullName()), data.cpf(), data.whatsapp(), body.birthDate(),
            blankToNull(body.adminNotes()), body.active() == null ? old.active() : body.active(), id, clinicId);
        return find(clinicId, id);
    }

    /** Não apaga: desativa, para preservar histórico de sessões e pacotes. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(Authentication auth, @PathVariable UUID id) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        find(clinicId, id);
        jdbc.update("UPDATE patients SET active = FALSE, updated_at = now() WHERE id = ? AND clinic_id = ?",
            id, clinicId);
    }

    PatientView find(UUID clinicId, UUID id) {
        return jdbc.query("SELECT * FROM patients WHERE id = ? AND clinic_id = ?", MAPPER, id, clinicId)
            .stream().findFirst().orElseThrow(NoSuchElementException::new);
    }

    private record Clean(String fullName, String cpf, String whatsapp) {}

    private static Clean clean(PatientRequest body) {
        String cpf = digitsOrNull(body.cpf());
        // CPF obrigatório: é a chave que evita paciente duplicado (como no agendamento pelo site) e vai no recibo.
        if (cpf == null) throw new IllegalArgumentException("Informe o CPF do paciente");
        if (!Cpf.isValid(cpf)) throw new IllegalArgumentException("CPF inválido");
        String whatsapp = digitsOrNull(body.whatsapp());
        if (whatsapp != null && (whatsapp.length() < 10 || whatsapp.length() > 13)) {
            throw new IllegalArgumentException("WhatsApp inválido: informe DDD e número");
        }
        if (body.birthDate() != null && body.birthDate().isBefore(LocalDate.of(1900, 1, 1))) {
            throw new IllegalArgumentException("Data de nascimento inválida");
        }
        return new Clean(body.fullName().trim().replaceAll("\\s+", " "), cpf, whatsapp);
    }

    private void requireUniqueCpf(UUID clinicId, String cpf, UUID self) {
        String other = jdbc.query("""
                SELECT full_name FROM patients WHERE clinic_id = ? AND cpf = ? AND (?::uuid IS NULL OR id <> ?::uuid)""",
            (rs, n) -> rs.getString(1), clinicId, cpf, self, self).stream().findFirst().orElse(null);
        if (other != null) throw new ConflictException("Já existe paciente com este CPF: " + other);
    }

    /** "  João  da SILVA " → "joao da silva". */
    public static String searchKey(String text) {
        String noAccents = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return noAccents.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String digitsOrNull(String value) {
        if (value == null) return null;
        String digits = value.replaceAll("\\D", "");
        return digits.isEmpty() ? null : digits;
    }
}
