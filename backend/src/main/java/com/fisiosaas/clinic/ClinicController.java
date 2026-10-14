package com.fisiosaas.clinic;

import com.fisiosaas.identity.CurrentClinic;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Perfil e identidade visual da clínica logada. */
@RestController
@RequestMapping("/api/v1/clinic")
public class ClinicController {
    static final String HEX = "^#[0-9A-Fa-f]{6}$";

    public record ClinicProfile(
        UUID id,
        @Pattern(regexp = "^[a-z0-9]([a-z0-9-]{1,61}[a-z0-9])?$",
            message = "use letras minúsculas, números e hífen (3 a 63 caracteres)") String slug,
        @NotBlank @Size(max = 120) String displayName,
        @Size(max = 120) String professionalName,
        @Size(max = 30) String crefito,
        @Size(max = 18) String document,
        @Size(max = 20) String whatsapp,
        @Size(max = 255) String address,
        @Size(max = 1000) String about,
        @Pattern(regexp = HEX, message = "cor inválida") String colorPrimary,
        @Pattern(regexp = HEX, message = "cor inválida") String colorSecondary,
        @Pattern(regexp = HEX, message = "cor inválida") String colorAccent,
        boolean publicPageEnabled) {}

    private final JdbcTemplate jdbc;
    private final CurrentClinic current;

    public ClinicController(JdbcTemplate jdbc, CurrentClinic current) {
        this.jdbc = jdbc;
        this.current = current;
    }

    @GetMapping
    public ClinicProfile get(Authentication auth) {
        return load(current.require(auth).clinicId());
    }

    @PutMapping
    public ClinicProfile update(Authentication auth, @Valid @RequestBody ClinicProfile body) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        ClinicProfile old = load(clinicId);
        jdbc.update("""
            UPDATE clinics SET slug = ?, display_name = ?, professional_name = ?, crefito = ?, document = ?,
                   whatsapp = ?, address = ?, about = ?, color_primary = ?, color_secondary = ?,
                   color_accent = ?, public_page_enabled = ?, updated_at = now()
             WHERE id = ?""",
            body.slug() == null ? old.slug() : body.slug(),
            body.displayName().trim(), blankToNull(body.professionalName()), blankToNull(body.crefito()),
            blankToNull(body.document()), digitsOrNull(body.whatsapp()), blankToNull(body.address()),
            blankToNull(body.about()),
            orDefault(body.colorPrimary(), old.colorPrimary()),
            orDefault(body.colorSecondary(), old.colorSecondary()),
            orDefault(body.colorAccent(), old.colorAccent()),
            body.publicPageEnabled(), clinicId);
        return load(clinicId);
    }

    private ClinicProfile load(UUID clinicId) {
        return jdbc.queryForObject("SELECT * FROM clinics WHERE id = ?", (rs, row) -> new ClinicProfile(
            rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("display_name"),
            rs.getString("professional_name"), rs.getString("crefito"), rs.getString("document"),
            rs.getString("whatsapp"), rs.getString("address"), rs.getString("about"),
            rs.getString("color_primary"), rs.getString("color_secondary"), rs.getString("color_accent"),
            rs.getBoolean("public_page_enabled")), clinicId);
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** WhatsApp guardado só com dígitos para montar links wa.me depois. */
    static String digitsOrNull(String value) {
        if (value == null) return null;
        String digits = value.replaceAll("\\D", "");
        return digits.isEmpty() ? null : digits;
    }

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value.toUpperCase();
    }
}
