package com.fisiosaas.catalog;

import com.fisiosaas.identity.CurrentClinic;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Pacotes de sessões (ex.: 10 sessões de Pilates). Preço opcional, exibição configurável. */
@RestController
@RequestMapping("/api/v1/packages")
public class PackageController {

    public record PackageRequest(
        @NotNull UUID serviceId,
        @NotBlank @Size(max = 120) String name,
        @NotNull @Min(1) @Max(200) Integer sessionCount,
        @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal price,
        Boolean showPrice,
        @Min(1) @Max(730) Integer validityDays,
        Boolean active) {}

    public record PackageView(UUID id, UUID serviceId, String serviceName, String name, int sessionCount,
                              BigDecimal price, boolean showPrice, Integer validityDays, boolean active) {}

    static final RowMapper<PackageView> MAPPER = (rs, row) -> new PackageView(
        rs.getObject("id", UUID.class), rs.getObject("service_id", UUID.class), rs.getString("service_name"),
        rs.getString("name"), rs.getInt("session_count"), rs.getBigDecimal("price"),
        rs.getBoolean("show_price"), (Integer) rs.getObject("validity_days"), rs.getBoolean("active"));

    private static final String SELECT = """
        SELECT p.*, s.name AS service_name
          FROM session_packages p JOIN services s ON s.id = p.service_id AND s.clinic_id = p.clinic_id
        """;

    private final JdbcTemplate jdbc;
    private final CurrentClinic current;

    public PackageController(JdbcTemplate jdbc, CurrentClinic current) {
        this.jdbc = jdbc;
        this.current = current;
    }

    @GetMapping
    public List<PackageView> list(Authentication auth) {
        return jdbc.query(SELECT + " WHERE p.clinic_id = ? ORDER BY p.active DESC, s.name, p.session_count",
            MAPPER, current.require(auth).clinicId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PackageView create(Authentication auth, @Valid @RequestBody PackageRequest body) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        requireOwnService(clinicId, body.serviceId());
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO session_packages(id, clinic_id, service_id, name, session_count, price, show_price,
                                         validity_days, active)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            id, clinicId, body.serviceId(), body.name().trim(), body.sessionCount(), body.price(),
            !Boolean.FALSE.equals(body.showPrice()), body.validityDays(), !Boolean.FALSE.equals(body.active()));
        return find(clinicId, id);
    }

    @PutMapping("/{id}")
    public PackageView update(Authentication auth, @PathVariable UUID id, @Valid @RequestBody PackageRequest body) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        PackageView old = find(clinicId, id);
        requireOwnService(clinicId, body.serviceId());
        jdbc.update("""
            UPDATE session_packages SET service_id = ?, name = ?, session_count = ?, price = ?, show_price = ?,
                   validity_days = ?, active = ?, updated_at = now()
             WHERE id = ? AND clinic_id = ?""",
            body.serviceId(), body.name().trim(), body.sessionCount(), body.price(),
            body.showPrice() == null ? old.showPrice() : body.showPrice(), body.validityDays(),
            body.active() == null ? old.active() : body.active(), id, clinicId);
        return find(clinicId, id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(Authentication auth, @PathVariable UUID id) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        find(clinicId, id);
        jdbc.update("UPDATE session_packages SET active = FALSE, updated_at = now() WHERE id = ? AND clinic_id = ?",
            id, clinicId);
    }

    private void requireOwnService(UUID clinicId, UUID serviceId) {
        Integer found = jdbc.queryForObject("SELECT count(*) FROM services WHERE id = ? AND clinic_id = ?",
            Integer.class, serviceId, clinicId);
        if (found == null || found == 0) throw new IllegalArgumentException("Serviço não encontrado");
    }

    private PackageView find(UUID clinicId, UUID id) {
        return jdbc.query(SELECT + " WHERE p.id = ? AND p.clinic_id = ?", MAPPER, id, clinicId)
            .stream().findFirst().orElseThrow(NoSuchElementException::new);
    }
}
