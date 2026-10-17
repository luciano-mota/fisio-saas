package com.fisiosaas.finance;

import com.fisiosaas.identity.CurrentClinic;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Maquininhas: débito, taxa de crédito à vista por prazo e crédito parcelado. */
@RestController
@RequestMapping("/api/v1/card-machines")
public class CardMachineController {
    public record InstallmentFee(@NotNull @Min(2) @Max(12) Integer installments,
                                 @NotNull @DecimalMin("0") @DecimalMax("30") @Digits(integer = 2, fraction = 2) BigDecimal fee) {}

    public record CreditTerm(@NotNull @Min(0) @Max(60) Integer days,
                             @NotNull @DecimalMin("0") @DecimalMax("30") @Digits(integer = 2, fraction = 2) BigDecimal fee) {}

    public record MachineRequest(
        @NotBlank @Size(max = 60) String name,
        @NotNull @DecimalMin("0") @DecimalMax("30") @Digits(integer = 2, fraction = 2) BigDecimal debitFee,
        @NotNull @DecimalMin("0") @DecimalMax("30") @Digits(integer = 2, fraction = 2) BigDecimal creditFee,
        @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal fixedFee,
        @Min(0) @Max(60) Integer debitDays,
        @Min(0) @Max(60) Integer creditDays,
        Boolean anticipated,
        @Size(max = 11) List<@Valid @NotNull InstallmentFee> installmentFees,
        @Size(max = 61) List<@Valid @NotNull CreditTerm> creditTerms) {}

    public record MachineView(UUID id, String name, BigDecimal debitFee, BigDecimal creditFee, BigDecimal fixedFee,
                              int debitDays, int creditDays, boolean anticipated, boolean active,
                              List<InstallmentFee> installmentFees, List<CreditTerm> creditTerms) {}

    private final JdbcTemplate jdbc;
    private final CurrentClinic current;

    public CardMachineController(JdbcTemplate jdbc, CurrentClinic current) {
        this.jdbc = jdbc;
        this.current = current;
    }

    @GetMapping
    public List<MachineView> list(Authentication auth) {
        UUID clinicId = current.require(auth).clinicId();
        return jdbc.queryForList("SELECT id FROM card_machines WHERE clinic_id = ? ORDER BY active DESC, name", UUID.class, clinicId)
            .stream().map(id -> find(clinicId, id)).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public MachineView create(Authentication auth, @Valid @RequestBody MachineRequest body) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO card_machines(id, clinic_id, name) VALUES (?, ?, ?)", id, clinicId, body.name().trim());
        write(clinicId, id, body);
        return find(clinicId, id);
    }

    @PutMapping("/{id}")
    @Transactional
    public MachineView update(Authentication auth, @PathVariable UUID id, @Valid @RequestBody MachineRequest body) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        find(clinicId, id);
        write(clinicId, id, body);
        return find(clinicId, id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(Authentication auth, @PathVariable UUID id) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        find(clinicId, id);
        jdbc.update("UPDATE card_machines SET active = FALSE WHERE id = ? AND clinic_id = ?", id, clinicId);
    }

    private void write(UUID clinicId, UUID id, MachineRequest body) {
        List<InstallmentFee> installments = body.installmentFees() == null ? List.of() : body.installmentFees();
        int days = body.creditDays() == null ? 30 : body.creditDays();
        List<CreditTerm> terms = body.creditTerms() == null
            ? List.of(new CreditTerm(days, body.creditFee())) : body.creditTerms();
        if (terms.isEmpty()) throw new IllegalArgumentException("Cadastre ao menos um prazo de crédito à vista");
        if (installments.stream().map(InstallmentFee::installments).distinct().count() != installments.size()) {
            throw new IllegalArgumentException("Parcela repetida nas taxas");
        }
        if (terms.stream().map(CreditTerm::days).distinct().count() != terms.size()) {
            throw new IllegalArgumentException("Prazo de crédito repetido");
        }
        boolean hasDefault = terms.stream().anyMatch(t -> t.days() == days && t.fee().compareTo(body.creditFee()) == 0);
        if (!hasDefault) throw new IllegalArgumentException("O prazo padrão precisa ter a mesma taxa de crédito à vista");
        jdbc.update("""
            UPDATE card_machines SET name = ?, debit_fee = ?, credit_fee = ?, fixed_fee = ?, debit_days = ?, credit_days = ?,
                   anticipated = ? WHERE id = ? AND clinic_id = ?""",
            body.name().trim(), body.debitFee(), body.creditFee(), body.fixedFee() == null ? BigDecimal.ZERO : body.fixedFee(),
            body.debitDays() == null ? 1 : body.debitDays(), days, Boolean.TRUE.equals(body.anticipated()), id, clinicId);
        jdbc.update("DELETE FROM card_machine_fees WHERE machine_id = ? AND clinic_id = ?", id, clinicId);
        for (InstallmentFee fee : installments) {
            jdbc.update("INSERT INTO card_machine_fees(clinic_id, machine_id, installments, fee) VALUES (?, ?, ?, ?)",
                clinicId, id, fee.installments(), fee.fee());
        }
        jdbc.update("DELETE FROM card_machine_credit_terms WHERE machine_id = ? AND clinic_id = ?", id, clinicId);
        for (CreditTerm term : terms) {
            jdbc.update("INSERT INTO card_machine_credit_terms(clinic_id, machine_id, days, fee) VALUES (?, ?, ?, ?)",
                clinicId, id, term.days(), term.fee());
        }
    }

    MachineView find(UUID clinicId, UUID id) {
        List<InstallmentFee> fees = jdbc.query(
            "SELECT installments, fee FROM card_machine_fees WHERE machine_id = ? AND clinic_id = ? ORDER BY installments",
            (rs, n) -> new InstallmentFee(rs.getInt(1), rs.getBigDecimal(2)), id, clinicId);
        List<CreditTerm> terms = jdbc.query(
            "SELECT days, fee FROM card_machine_credit_terms WHERE machine_id = ? AND clinic_id = ? ORDER BY days",
            (rs, n) -> new CreditTerm(rs.getInt(1), rs.getBigDecimal(2)), id, clinicId);
        return jdbc.query("SELECT * FROM card_machines WHERE id = ? AND clinic_id = ?",
                (rs, n) -> new MachineView(rs.getObject("id", UUID.class), rs.getString("name"), rs.getBigDecimal("debit_fee"),
                    rs.getBigDecimal("credit_fee"), rs.getBigDecimal("fixed_fee"), rs.getInt("debit_days"),
                    rs.getInt("credit_days"), rs.getBoolean("anticipated"), rs.getBoolean("active"), fees, terms),
                id, clinicId)
            .stream().findFirst().orElseThrow(NoSuchElementException::new);
    }

    /** Maquininha desativada ou de outra clínica não serve para calcular. */
    CardFees.Machine fees(UUID clinicId, UUID id) {
        MachineView m = find(clinicId, id);
        if (!m.active()) throw new IllegalArgumentException("Maquininha desativada");
        Map<Integer, BigDecimal> byInstallments = m.installmentFees().stream()
            .collect(Collectors.toMap(InstallmentFee::installments, InstallmentFee::fee));
        Map<Integer, BigDecimal> byDays = m.creditTerms().stream()
            .collect(Collectors.toMap(CreditTerm::days, CreditTerm::fee));
        return new CardFees.Machine(m.debitFee(), m.creditFee(), byInstallments, m.fixedFee(), m.debitDays(), m.creditDays(),
            m.anticipated(), byDays);
    }
}
