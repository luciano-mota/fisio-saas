package com.fisiosaas.catalog;

import com.fisiosaas.identity.CurrentClinic;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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

/** Serviços oferecidos pela clínica (painel admin). Preço é opcional. */
@RestController
@RequestMapping("/api/v1/services")
public class ServiceController {

    public record ServiceRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 600) String description,
        @Min(5) @Max(480) Integer durationMinutes,
        @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal price,
        Boolean showPrice,
        Boolean onlineBookingEnabled,
        Boolean active,
        Integer displayOrder) {}

    public record ServiceView(UUID id, String name, String description, int durationMinutes,
                              BigDecimal price, boolean showPrice, boolean onlineBookingEnabled,
                              boolean active, int displayOrder) {}

    static final RowMapper<ServiceView> MAPPER = (rs, row) -> new ServiceView(
        rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"),
        rs.getInt("duration_minutes"), rs.getBigDecimal("price"), rs.getBoolean("show_price"),
        rs.getBoolean("online_booking_enabled"), rs.getBoolean("active"), rs.getInt("display_order"));

    private final JdbcTemplate jdbc;
    private final CurrentClinic current;

    public ServiceController(JdbcTemplate jdbc, CurrentClinic current) {
        this.jdbc = jdbc;
        this.current = current;
    }

    @GetMapping
    public List<ServiceView> list(Authentication auth) {
        return jdbc.query("SELECT * FROM services WHERE clinic_id = ? ORDER BY active DESC, display_order, name",
            MAPPER, current.require(auth).clinicId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ServiceView create(Authentication auth, @Valid @RequestBody ServiceRequest body) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO services(id, clinic_id, name, description, duration_minutes, price, show_price,
                                 online_booking_enabled, active, display_order)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            id, clinicId, body.name().trim(), blankToNull(body.description()),
            body.durationMinutes() == null ? 50 : body.durationMinutes(), body.price(),
            !Boolean.FALSE.equals(body.showPrice()), !Boolean.FALSE.equals(body.onlineBookingEnabled()),
            !Boolean.FALSE.equals(body.active()), body.displayOrder() == null ? 0 : body.displayOrder());
        return find(clinicId, id);
    }

    @PutMapping("/{id}")
    public ServiceView update(Authentication auth, @PathVariable UUID id, @Valid @RequestBody ServiceRequest body) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        ServiceView old = find(clinicId, id);
        jdbc.update("""
            UPDATE services SET name = ?, description = ?, duration_minutes = ?, price = ?, show_price = ?,
                   online_booking_enabled = ?, active = ?, display_order = ?, updated_at = now()
             WHERE id = ? AND clinic_id = ?""",
            body.name().trim(), blankToNull(body.description()),
            body.durationMinutes() == null ? old.durationMinutes() : body.durationMinutes(), body.price(),
            body.showPrice() == null ? old.showPrice() : body.showPrice(),
            body.onlineBookingEnabled() == null ? old.onlineBookingEnabled() : body.onlineBookingEnabled(),
            body.active() == null ? old.active() : body.active(),
            body.displayOrder() == null ? old.displayOrder() : body.displayOrder(), id, clinicId);
        return find(clinicId, id);
    }

    /** Não apaga: desativa, para preservar histórico de pacotes e agendamentos. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(Authentication auth, @PathVariable UUID id) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        find(clinicId, id);
        jdbc.update("UPDATE services SET active = FALSE, updated_at = now() WHERE id = ? AND clinic_id = ?",
            id, clinicId);
        jdbc.update("UPDATE session_packages SET active = FALSE, updated_at = now() WHERE service_id = ? AND clinic_id = ?",
            id, clinicId);
    }

    ServiceView find(UUID clinicId, UUID id) {
        return jdbc.query("SELECT * FROM services WHERE id = ? AND clinic_id = ?", MAPPER, id, clinicId)
            .stream().findFirst().orElseThrow(NoSuchElementException::new);
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
