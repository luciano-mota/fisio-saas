package com.fisiosaas.patientpackage;

import com.fisiosaas.agenda.Agenda;
import com.fisiosaas.identity.CurrentClinic;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Venda de pacote ao paciente e saldo de sessões: compradas, realizadas (consumidas), agendadas e restantes.
 * Aviso de renovação quando restam 2 ou menos.
 */
@RestController
@RequestMapping("/api/v1")
public class PatientPackageController {
    static final int LOW_BALANCE = 2;

    public record SaleRequest(
        @NotNull UUID packageId,
        LocalDate purchasedOn,
        @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal price,
        @Size(max = 500) String notes) {}

    public record UpdateRequest(
        @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal price,
        LocalDate expiresOn,
        @Size(max = 500) String notes) {}

    public record PatientPackageView(UUID id, UUID patientId, String patientName, String patientWhatsapp,
                                     UUID packageId, UUID serviceId, String serviceName, String name,
                                     int sessionCount, BigDecimal price, LocalDate purchasedOn, LocalDate expiresOn,
                                     String notes, boolean active, int used, int scheduled, int remaining,
                                     boolean expired, boolean lowBalance) {}

    private static final String SELECT = """
        SELECT pp.*, p.full_name AS patient_name, p.whatsapp AS patient_whatsapp, s.name AS service_name,
               (SELECT count(*) FROM appointments a WHERE a.clinic_id = pp.clinic_id AND a.patient_package_id = pp.id
                   AND a.counts_against_package) AS used,
               (SELECT count(*) FROM appointments a WHERE a.clinic_id = pp.clinic_id AND a.patient_package_id = pp.id
                   AND a.status IN ('SCHEDULED', 'CONFIRMED')) AS scheduled
          FROM patient_packages pp
          JOIN patients p ON p.id = pp.patient_id AND p.clinic_id = pp.clinic_id
          JOIN services s ON s.id = pp.service_id AND s.clinic_id = pp.clinic_id
        """;

    private final JdbcTemplate jdbc;
    private final CurrentClinic current;
    private final Agenda agenda;
    private final PackageLedger ledger;

    public PatientPackageController(JdbcTemplate jdbc, CurrentClinic current, Agenda agenda, PackageLedger ledger) {
        this.jdbc = jdbc;
        this.current = current;
        this.agenda = agenda;
        this.ledger = ledger;
    }

    @GetMapping("/patients/{patientId}/packages")
    public List<PatientPackageView> ofPatient(Authentication auth, @PathVariable UUID patientId) {
        UUID clinicId = current.require(auth).clinicId();
        requirePatient(clinicId, patientId);
        return query(clinicId, " WHERE pp.clinic_id = ? AND pp.patient_id = ? ORDER BY pp.active DESC, pp.purchased_on DESC, pp.created_at DESC",
            clinicId, patientId);
    }

    /** Pacotes ativos com 2 sessões ou menos (hora de oferecer renovação). */
    @GetMapping("/patient-packages/low-balance")
    public List<PatientPackageView> lowBalance(Authentication auth) {
        UUID clinicId = current.require(auth).clinicId();
        return query(clinicId, " WHERE pp.clinic_id = ? AND pp.active ORDER BY p.search_name", clinicId)
            .stream().filter(PatientPackageView::lowBalance).toList();
    }

    @PostMapping("/patients/{patientId}/packages")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public PatientPackageView sell(Authentication auth, @PathVariable UUID patientId, @Valid @RequestBody SaleRequest body) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        requirePatient(clinicId, patientId);
        Integer active = jdbc.queryForObject("SELECT count(*) FROM patients WHERE id = ? AND clinic_id = ? AND active",
            Integer.class, patientId, clinicId);
        if (active == null || active == 0) throw new IllegalArgumentException("Paciente desativado");

        record Catalog(UUID serviceId, String name, int sessions, BigDecimal price, Integer validityDays) {}
        Catalog item = jdbc.query("""
                SELECT p.service_id, p.name, p.session_count, p.price, p.validity_days
                  FROM session_packages p JOIN services s ON s.id = p.service_id AND s.clinic_id = p.clinic_id
                 WHERE p.id = ? AND p.clinic_id = ? AND p.active AND s.active""",
            (rs, n) -> new Catalog(rs.getObject("service_id", UUID.class), rs.getString("name"),
                rs.getInt("session_count"), rs.getBigDecimal("price"), (Integer) rs.getObject("validity_days")),
            body.packageId(), clinicId).stream().findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Pacote não encontrado"));

        LocalDate purchasedOn = body.purchasedOn() == null ? LocalDate.now(agenda.zone(clinicId)) : body.purchasedOn();
        LocalDate expiresOn = item.validityDays() == null ? null : purchasedOn.plusDays(item.validityDays());
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO patient_packages(id, clinic_id, patient_id, package_id, service_id, name, session_count, price,
                                         purchased_on, expires_on, notes)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            id, clinicId, patientId, body.packageId(), item.serviceId(), item.name(), item.sessions(),
            body.price() != null ? body.price() : item.price(), purchasedOn, expiresOn, blankToNull(body.notes()));

        // Sessões já marcadas deste serviço, ainda sem pacote, passam a usar o pacote recém-vendido.
        List<UUID> open = jdbc.query("""
                SELECT id FROM appointments
                 WHERE clinic_id = ? AND patient_id = ? AND service_id = ? AND patient_package_id IS NULL
                   AND status IN ('SCHEDULED', 'CONFIRMED')
                 ORDER BY starts_at""",
            (rs, n) -> rs.getObject("id", UUID.class), clinicId, patientId, item.serviceId());
        open.forEach(appointmentId -> ledger.linkNew(clinicId, appointmentId));
        return find(clinicId, id);
    }

    /** Corrige valor, validade ou observação. Quantidade e serviço não mudam depois da venda. */
    @PutMapping("/patient-packages/{id}")
    public PatientPackageView update(Authentication auth, @PathVariable UUID id, @Valid @RequestBody UpdateRequest body) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        PatientPackageView old = find(clinicId, id);
        if (body.expiresOn() != null && body.expiresOn().isBefore(old.purchasedOn())) {
            throw new IllegalArgumentException("Validade antes da data da compra");
        }
        jdbc.update("""
            UPDATE patient_packages SET price = ?, expires_on = ?, notes = ?, updated_at = now()
             WHERE id = ? AND clinic_id = ?""",
            body.price(), body.expiresOn(), blankToNull(body.notes()), id, clinicId);
        return find(clinicId, id);
    }

    /** Não apaga: desativa. Sessões em aberto ficam avulsas; as já realizadas continuam no histórico do pacote. */
    @DeleteMapping("/patient-packages/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deactivate(Authentication auth, @PathVariable UUID id) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        find(clinicId, id);
        jdbc.update("UPDATE patient_packages SET active = FALSE, updated_at = now() WHERE id = ? AND clinic_id = ?",
            id, clinicId);
        ledger.releaseOpen(clinicId, id);
    }

    PatientPackageView find(UUID clinicId, UUID id) {
        return query(clinicId, " WHERE pp.id = ? AND pp.clinic_id = ?", id, clinicId)
            .stream().findFirst().orElseThrow(NoSuchElementException::new);
    }

    private List<PatientPackageView> query(UUID clinicId, String where, Object... args) {
        LocalDate today = LocalDate.now(agenda.zone(clinicId));
        return jdbc.query(SELECT + where, (rs, n) -> {
            int count = rs.getInt("session_count");
            int used = rs.getInt("used");
            LocalDate expiresOn = rs.getObject("expires_on", LocalDate.class);
            boolean active = rs.getBoolean("active");
            return new PatientPackageView(
                rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class), rs.getString("patient_name"),
                rs.getString("patient_whatsapp"), rs.getObject("package_id", UUID.class),
                rs.getObject("service_id", UUID.class), rs.getString("service_name"), rs.getString("name"), count,
                rs.getBigDecimal("price"), rs.getObject("purchased_on", LocalDate.class), expiresOn,
                rs.getString("notes"), active, used, rs.getInt("scheduled"), count - used,
                expiresOn != null && expiresOn.isBefore(today), active && count - used <= LOW_BALANCE);
        }, args);
    }

    private void requirePatient(UUID clinicId, UUID patientId) {
        Integer found = jdbc.queryForObject("SELECT count(*) FROM patients WHERE id = ? AND clinic_id = ?",
            Integer.class, patientId, clinicId);
        if (found == null || found == 0) throw new NoSuchElementException();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
