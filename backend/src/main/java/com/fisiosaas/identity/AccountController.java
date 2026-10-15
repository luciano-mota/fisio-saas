package com.fisiosaas.identity;

import com.fisiosaas.api.ConflictException;
import com.fisiosaas.identity.CurrentClinic.ClinicUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Minha conta (troca de senha) e equipe da clínica. A responsável (OWNER) dá acesso a colaboradores (STAFF, só
 * consulta) ou a outra responsável; o acesso nasce com senha temporária, trocada no primeiro login. Nada é apagado:
 * remover = desativar. A clínica nunca fica sem uma responsável ativa.
 */
@RestController
@RequestMapping("/api/v1")
public class AccountController {
    private static final Logger log = LoggerFactory.getLogger(AccountController.class);
    static final int MIN_PASSWORD = 12;

    public record PasswordChange(@NotBlank String current, @NotBlank @Size(max = 128) String password) {}

    public record Member(UUID id, String email, String fullName, String role, boolean active, boolean mustChangePassword,
                         OffsetDateTime createdAt, boolean me) {}

    public record NewMember(@NotBlank @Email @Size(max = 255) String email, @Size(max = 120) String fullName,
                            @NotNull Role role) {}

    public record MemberUpdate(@Size(max = 120) String fullName, @NotNull Role role) {}

    /** Senha temporária mostrada uma única vez para a responsável repassar. */
    public record Access(Member member, String temporaryPassword) {}

    public enum Role { OWNER, STAFF }

    private final JdbcTemplate jdbc;
    private final CurrentClinic current;
    private final PasswordEncoder passwords;

    public AccountController(JdbcTemplate jdbc, CurrentClinic current, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.current = current;
        this.passwords = passwords;
    }

    @PutMapping("/account/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(Authentication auth, @Valid @RequestBody PasswordChange body) {
        ClinicUser user = current.require(auth);
        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, user.userId());
        if (!passwords.matches(body.current(), hash)) throw new IllegalArgumentException("Senha atual incorreta");
        validatePassword(body.password(), user.email());
        if (passwords.matches(body.password(), hash)) throw new IllegalArgumentException("A nova senha precisa ser diferente da atual");
        jdbc.update("UPDATE users SET password_hash = ?, must_change_password = FALSE, password_changed_at = now() WHERE id = ?",
            passwords.encode(body.password()), user.userId());
        log.info("Senha trocada: usuario={}", user.userId());
    }

    @GetMapping("/team")
    public List<Member> team(Authentication auth) {
        ClinicUser user = current.require(auth);
        return jdbc.query("""
                SELECT * FROM users WHERE clinic_id = ?
                 ORDER BY active DESC, CASE role WHEN 'OWNER' THEN 0 ELSE 1 END, lower(coalesce(full_name, email))""",
            (rs, n) -> member(rs, user), user.clinicId());
    }

    @PostMapping("/team")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Access add(Authentication auth, @Valid @RequestBody NewMember body) {
        ClinicUser owner = current.requireOwner(auth);
        String email = body.email().trim().toLowerCase(Locale.ROOT);
        Integer taken = jdbc.queryForObject("SELECT count(*) FROM users WHERE lower(email) = ?", Integer.class, email);
        // Não diz se o e-mail é de outra clínica: só que já tem acesso.
        if (taken != null && taken > 0) throw new ConflictException("Este e-mail já tem acesso ao sistema. Use outro e-mail.");
        String temporary = temporaryPassword();
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO users(id, clinic_id, email, password_hash, full_name, role, must_change_password)
            VALUES (?, ?, ?, ?, ?, ?, TRUE)""",
            id, owner.clinicId(), email, passwords.encode(temporary), blankToNull(body.fullName()), body.role().name());
        log.info("Acesso criado: clinica={} usuario={} papel={}", owner.clinicId(), id, body.role());
        return new Access(find(owner, id), temporary);
    }

    @PutMapping("/team/{id}")
    @Transactional
    public Member update(Authentication auth, @PathVariable UUID id, @Valid @RequestBody MemberUpdate body) {
        ClinicUser owner = current.requireOwner(auth);
        Member member = lockMember(owner, id);
        if (member.role().equals("OWNER") && body.role() == Role.STAFF) requireAnotherOwner(owner, id);
        jdbc.update("UPDATE users SET full_name = ?, role = ? WHERE id = ? AND clinic_id = ?",
            blankToNull(body.fullName()), body.role().name(), id, owner.clinicId());
        return find(owner, id);
    }

    @PostMapping("/team/{id}/reset-password")
    public Access resetPassword(Authentication auth, @PathVariable UUID id) {
        ClinicUser owner = current.requireOwner(auth);
        Member member = lockMember(owner, id);
        if (member.me()) throw new IllegalArgumentException("Para a sua senha, use Minha conta → Trocar senha");
        String temporary = temporaryPassword();
        jdbc.update("UPDATE users SET password_hash = ?, must_change_password = TRUE WHERE id = ? AND clinic_id = ?",
            passwords.encode(temporary), id, owner.clinicId());
        endSessions(member.email());
        log.info("Senha redefinida pela responsável: clinica={} usuario={}", owner.clinicId(), id);
        return new Access(find(owner, id), temporary);
    }

    @DeleteMapping("/team/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deactivate(Authentication auth, @PathVariable UUID id) {
        ClinicUser owner = current.requireOwner(auth);
        Member member = lockMember(owner, id);
        if (member.me()) throw new IllegalArgumentException("Você não pode remover o seu próprio acesso");
        if (member.role().equals("OWNER")) requireAnotherOwner(owner, id);
        jdbc.update("UPDATE users SET active = FALSE WHERE id = ? AND clinic_id = ?", id, owner.clinicId());
        endSessions(member.email());
    }

    @PostMapping("/team/{id}/reactivate")
    public Member reactivate(Authentication auth, @PathVariable UUID id) {
        ClinicUser owner = current.requireOwner(auth);
        lockMember(owner, id);
        jdbc.update("UPDATE users SET active = TRUE WHERE id = ? AND clinic_id = ?", id, owner.clinicId());
        return find(owner, id);
    }

    /** Senha forte o bastante para o MVP: 12+ caracteres, não só números e diferente do e-mail. */
    static void validatePassword(String password, String email) {
        if (password.length() < MIN_PASSWORD) throw new IllegalArgumentException("A senha precisa ter pelo menos 12 caracteres");
        if (password.chars().allMatch(Character::isDigit)) throw new IllegalArgumentException("Use letras na senha, não só números");
        String local = email.split("@")[0].toLowerCase(Locale.ROOT);
        if (password.equalsIgnoreCase(email) || (local.length() >= 4 && password.toLowerCase(Locale.ROOT).contains(local))) {
            throw new IllegalArgumentException("A senha não pode conter o seu e-mail");
        }
    }

    /** Derruba os logins abertos da pessoa (acesso removido ou senha redefinida). */
    private void endSessions(String email) {
        jdbc.update("DELETE FROM spring_session WHERE lower(principal_name) = lower(?)", email);
    }

    private void requireAnotherOwner(ClinicUser owner, UUID except) {
        Integer others = jdbc.queryForObject(
            "SELECT count(*) FROM users WHERE clinic_id = ? AND role = 'OWNER' AND active AND id <> ?",
            Integer.class, owner.clinicId(), except);
        if (others == null || others == 0) throw new IllegalArgumentException("A clínica precisa ter pelo menos uma responsável ativa");
    }

    /** Linhas da própria clínica; travadas para duas ações seguidas não deixarem a clínica sem responsável. */
    private Member lockMember(ClinicUser owner, UUID id) {
        jdbc.query("SELECT id FROM users WHERE clinic_id = ? AND role = 'OWNER' FOR UPDATE", (rs, n) -> 0, owner.clinicId());
        return find(owner, id);
    }

    private Member find(ClinicUser viewer, UUID id) {
        return jdbc.query("SELECT * FROM users WHERE id = ? AND clinic_id = ?", (rs, n) -> member(rs, viewer), id, viewer.clinicId())
            .stream().findFirst().orElseThrow(NoSuchElementException::new);
    }

    private static Member member(java.sql.ResultSet rs, ClinicUser viewer) throws java.sql.SQLException {
        UUID id = rs.getObject("id", UUID.class);
        return new Member(id, rs.getString("email"), rs.getString("full_name"), rs.getString("role"), rs.getBoolean("active"),
            rs.getBoolean("must_change_password"), rs.getObject("created_at", OffsetDateTime.class), id.equals(viewer.userId()));
    }

    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 14 caracteres sem letras/números que se confundem, em blocos para ditar ("abcd-efgh-jkmn"). */
    static String temporaryPassword() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            if (i > 0 && i % 4 == 0) out.append('-');
            out.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return out.toString();
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
