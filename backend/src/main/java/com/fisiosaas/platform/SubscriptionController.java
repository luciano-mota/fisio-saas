package com.fisiosaas.platform;

import com.fisiosaas.finance.CashFlowController;
import com.fisiosaas.identity.CurrentClinic;
import com.fisiosaas.pix.PixCode;
import com.fisiosaas.pix.PixCode.KeyType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Financeiro dos assinantes. A plataforma define o plano de cada clínica (valor e dia de vencimento) e gera as
 * mensalidades. A clínica vê a mensalidade com o QR Code Pix da plataforma e avisa "pagamento realizado" (fica
 * pendente para os dois lados); a plataforma confirma: vira receita da plataforma e despesa da clínica.
 */
@RestController
public class SubscriptionController {
    static final Locale BR = Locale.of("pt", "BR");
    static final List<String> METHODS = List.of("PIX", "CARTAO", "BOLETO", "DINHEIRO", "OUTRO");

    public record Plan(BigDecimal planPrice, Integer billingDay) {}

    public record PlanRequest(@DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal planPrice,
                              @Min(1) @Max(28) Integer billingDay) {}

    public record ChargeView(UUID id, UUID clinicId, String clinicName, LocalDate referenceMonth, String description,
                             BigDecimal amount, LocalDate dueOn, String status, boolean overdue, OffsetDateTime informedAt,
                             String informedBy, LocalDate paidOn, BigDecimal paidAmount, String method, String confirmedBy,
                             String notes) {}

    public record NewCharge(@NotNull LocalDate referenceMonth,
                            @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal amount,
                            @NotNull LocalDate dueOn, @Size(max = 120) String description) {}

    public record Confirm(LocalDate paidOn, @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal paidAmount,
                          String method, @Size(max = 300) String notes, Boolean renewAccess) {}

    public record Reason(@NotBlank(message = "Informe o motivo") @Size(max = 300) String reason) {}

    public record Total(int count, BigDecimal amount) {}

    /** Resumo do mês para a plataforma. mrr = soma dos planos das clínicas assinantes (ativas). */
    public record Overview(String month, LocalDate today, BigDecimal mrr, Total expected, Total received, Total informed,
                           Total open, Total overdue, List<ChargeView> charges) {}

    public record Generated(int created, int skipped) {}

    public record PixSettings(KeyType keyType, @Size(max = 77) String key, @Size(max = 60) String name,
                              @Size(max = 40) String city) {}

    public record PixCharge(String code, String qrSvg, BigDecimal amount, String receiver) {}

    /** O que a clínica vê: o plano, as mensalidades (despesas com a PhysioEvo) e se dá para pagar por Pix. */
    public record MySubscription(Plan plan, boolean pixAvailable, List<ChargeView> charges) {}

    private final JdbcTemplate jdbc;
    private final CurrentClinic current;
    private final ClinicAccess access;

    public SubscriptionController(JdbcTemplate jdbc, CurrentClinic current, ClinicAccess access) {
        this.jdbc = jdbc;
        this.current = current;
        this.access = access;
    }

    static LocalDate today() {
        return LocalDate.now(ZoneId.of("America/Fortaleza"));
    }

    private static final String SELECT = """
        SELECT s.*, c.display_name AS clinic_name FROM subscription_charges s JOIN clinics c ON c.id = s.clinic_id
        """;

    private static ChargeView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        LocalDate due = rs.getObject("due_on", LocalDate.class);
        String status = rs.getString("status");
        return new ChargeView(rs.getObject("id", UUID.class), rs.getObject("clinic_id", UUID.class), rs.getString("clinic_name"),
            rs.getObject("reference_month", LocalDate.class), rs.getString("description"), rs.getBigDecimal("amount"), due,
            status, "OPEN".equals(status) && due.isBefore(today()), rs.getObject("informed_at", OffsetDateTime.class),
            rs.getString("informed_by"), rs.getObject("paid_on", LocalDate.class), rs.getBigDecimal("paid_amount"),
            rs.getString("method"), rs.getString("confirmed_by"), rs.getString("notes"));
    }

    // ================= Plataforma =================

    @GetMapping("/api/v1/admin/clinics/{id}/plan")
    public Plan plan(@PathVariable UUID id) {
        return jdbc.query("SELECT plan_price, billing_day FROM clinics WHERE id = ?",
                (rs, n) -> new Plan(rs.getBigDecimal(1), (Integer) rs.getObject(2)), id)
            .stream().findFirst().orElseThrow(NoSuchElementException::new);
    }

    /** Valor por clínica (promoções). Sem valor = sem mensalidade automática. */
    @PutMapping("/api/v1/admin/clinics/{id}/plan")
    public Plan savePlan(@PathVariable UUID id, @Valid @RequestBody PlanRequest body) {
        plan(id);
        jdbc.update("UPDATE clinics SET plan_price = ?, billing_day = ? WHERE id = ?", body.planPrice(), body.billingDay(), id);
        return plan(id);
    }

    @GetMapping("/api/v1/admin/clinics/{id}/charges")
    public List<ChargeView> clinicCharges(@PathVariable UUID id) {
        plan(id);
        return jdbc.query(SELECT + " WHERE s.clinic_id = ? ORDER BY s.due_on DESC, s.created_at DESC", (rs, n) -> map(rs), id);
    }

    /** Cobrança avulsa ou de um mês específico (ex.: promoção, plano anual). */
    @PostMapping("/api/v1/admin/clinics/{id}/charges")
    @ResponseStatus(HttpStatus.CREATED)
    public ChargeView newCharge(Authentication auth, @PathVariable UUID id, @Valid @RequestBody NewCharge body) {
        plan(id);
        LocalDate month = body.referenceMonth().withDayOfMonth(1);
        requireNoChargeFor(id, month);
        UUID chargeId = UUID.randomUUID();
        String description = body.description() == null || body.description().isBlank() ? monthLabel(month) : body.description().trim();
        jdbc.update("""
            INSERT INTO subscription_charges(id, clinic_id, reference_month, description, amount, due_on, status, created_by)
            VALUES (?, ?, ?, ?, ?, ?, 'OPEN', ?)""", chargeId, id, month, description, body.amount(), body.dueOn(), auth.getName());
        return find(chargeId);
    }

    /**
     * Gera a mensalidade do mês para as clínicas com valor de plano que estão como assinantes (ativas, mesmo vencidas,
     * ou suspensas). Teste grátis, acesso gratuito e bloqueadas ficam de fora. Não duplica.
     */
    @PostMapping("/api/v1/admin/finance/generate")
    @Transactional
    public Generated generate(Authentication auth, @RequestParam String month) {
        YearMonth ym = parseMonth(month);
        LocalDate ref = ym.atDay(1);
        record Target(UUID id, BigDecimal price, Integer day) {}
        List<Target> targets = jdbc.query("""
                SELECT id, plan_price, billing_day FROM clinics
                 WHERE plan_price > 0 AND status IN ('ACTIVE', 'SUSPENDED')""",
            (rs, n) -> new Target(rs.getObject(1, UUID.class), rs.getBigDecimal(2), (Integer) rs.getObject(3)));
        int created = 0;
        int skipped = 0;
        for (Target t : targets) {
            int inserted = jdbc.update("""
                INSERT INTO subscription_charges(id, clinic_id, reference_month, description, amount, due_on, status, created_by)
                VALUES (?, ?, ?, ?, ?, ?, 'OPEN', ?)
                ON CONFLICT (clinic_id, reference_month) WHERE status <> 'CANCELED' DO NOTHING""",
                UUID.randomUUID(), t.id(), ref, monthLabel(ref), t.price(), ym.atDay(t.day() == null ? 10 : t.day()), auth.getName());
            if (inserted > 0) created++; else skipped++;
        }
        return new Generated(created, skipped);
    }

    /** Resumo do mês + lista: mensalidades do mês, as informadas (a confirmar) e as atrasadas de qualquer mês. */
    @GetMapping("/api/v1/admin/finance")
    public Overview overview(@RequestParam String month) {
        YearMonth ym = parseMonth(month);
        LocalDate from = ym.atDay(1);
        LocalDate to = ym.atEndOfMonth();
        LocalDate today = today();
        BigDecimal mrr = jdbc.queryForObject("SELECT coalesce(sum(plan_price), 0) FROM clinics WHERE status = 'ACTIVE' AND plan_price > 0",
            BigDecimal.class);
        Total expected = total("reference_month = ? AND status <> 'CANCELED'", "amount", from);
        Total received = total("status = 'PAID' AND paid_on BETWEEN ? AND ?", "paid_amount", from, to);
        Total informed = total("status = 'INFORMED'", "amount");
        Total open = total("status = 'OPEN'", "amount");
        Total overdue = total("status = 'OPEN' AND due_on < ?", "amount", today);
        List<ChargeView> charges = jdbc.query(SELECT + """
                 WHERE s.reference_month = ? OR s.status = 'INFORMED' OR (s.status = 'OPEN' AND s.due_on < ?)
                 ORDER BY CASE s.status WHEN 'INFORMED' THEN 0 WHEN 'OPEN' THEN 1 ELSE 2 END, s.due_on, c.display_name""",
            (rs, n) -> map(rs), from, today);
        return new Overview(ym.toString(), today, mrr, expected, received, informed, open, overdue, charges);
    }

    /** Confirma o recebimento: receita da plataforma e despesa da clínica. Pode renovar o acesso até o próximo vencimento. */
    @PostMapping("/api/v1/admin/charges/{id}/confirm")
    @Transactional
    public ChargeView confirm(Authentication auth, @PathVariable UUID id, @Valid @RequestBody Confirm body) {
        ChargeView charge = lock(id);
        if (!"OPEN".equals(charge.status()) && !"INFORMED".equals(charge.status())) {
            throw new IllegalArgumentException("Esta mensalidade já foi " + ("PAID".equals(charge.status()) ? "paga" : "cancelada"));
        }
        String method = body.method() == null ? "PIX" : body.method().trim().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) throw new IllegalArgumentException("Forma de pagamento inválida");
        LocalDate paidOn = body.paidOn() == null ? today() : body.paidOn();
        if (paidOn.isAfter(today())) throw new IllegalArgumentException("A data do pagamento não pode ser no futuro");
        BigDecimal paid = body.paidAmount() == null ? charge.amount() : body.paidAmount();
        jdbc.update("""
            UPDATE subscription_charges SET status = 'PAID', paid_on = ?, paid_amount = ?, method = ?, notes = coalesce(?, notes),
                   confirmed_by = ?, confirmed_at = now(), updated_at = now() WHERE id = ?""",
            paidOn, paid, method, blankToNull(body.notes()), auth.getName(), id);
        if (!Boolean.FALSE.equals(body.renewAccess())) renew(auth, charge);
        return find(id);
    }

    /** "Não recebi": volta a mensalidade informada para a pagar, com o motivo (a clínica vê). */
    @PostMapping("/api/v1/admin/charges/{id}/reject")
    @Transactional
    public ChargeView reject(@PathVariable UUID id, @Valid @RequestBody Reason body) {
        ChargeView charge = lock(id);
        if (!"INFORMED".equals(charge.status())) throw new IllegalArgumentException("Só dá para recusar um pagamento informado");
        jdbc.update("""
            UPDATE subscription_charges SET status = 'OPEN', informed_at = NULL, informed_by = NULL, notes = ?, updated_at = now()
             WHERE id = ?""", "Pagamento não identificado: " + body.reason().trim(), id);
        return find(id);
    }

    @PostMapping("/api/v1/admin/charges/{id}/cancel")
    @Transactional
    public ChargeView cancel(@PathVariable UUID id, @Valid @RequestBody Reason body) {
        ChargeView charge = lock(id);
        if ("PAID".equals(charge.status()) || "CANCELED".equals(charge.status())) {
            throw new IllegalArgumentException("Esta mensalidade já foi " + ("PAID".equals(charge.status()) ? "paga" : "cancelada"));
        }
        jdbc.update("UPDATE subscription_charges SET status = 'CANCELED', notes = ?, updated_at = now() WHERE id = ?",
            "Cancelada: " + body.reason().trim(), id);
        return find(id);
    }

    /** Fluxo de caixa da plataforma: mensalidades confirmadas no período (por data de pagamento). */
    @GetMapping("/api/v1/admin/finance/cashflow")
    public CashFlowController.CashFlow cashFlow(
            @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate to) {
        CashFlowController.validate(from, to);
        List<CashFlowController.Entry> entries = new java.util.ArrayList<>(jdbc.query(SELECT + """
                 WHERE s.status = 'PAID' AND s.paid_on BETWEEN ? AND ?""",
            (rs, n) -> new CashFlowController.Entry(rs.getObject("paid_on", LocalDate.class), "IN", rs.getString("description"),
                rs.getString("clinic_name"), rs.getString("method"), rs.getBigDecimal("paid_amount"), BigDecimal.ZERO,
                rs.getBigDecimal("paid_amount")), from, to));
        return CashFlowController.build(new CashFlowController.Header("PhysioEvo", null, null, null, null, null), from, to, entries);
    }

        @GetMapping("/api/v1/admin/settings/pix")
    public PixSettings pixSettings() {
        return jdbc.queryForObject("SELECT pix_key_type, pix_key, pix_name, pix_city FROM platform_settings WHERE id = 1",
            (rs, n) -> new PixSettings(rs.getString(1) == null ? null : KeyType.valueOf(rs.getString(1)), rs.getString(2),
                rs.getString(3), rs.getString(4)));
    }

    @PutMapping("/api/v1/admin/settings/pix")
    public PixSettings savePixSettings(@Valid @RequestBody PixSettings body) {
        if (body.keyType() == null) {
            jdbc.update("UPDATE platform_settings SET pix_key_type = NULL, pix_key = NULL, pix_name = NULL, pix_city = NULL, updated_at = now()");
            return pixSettings();
        }
        String key = PixCode.normalizeKey(body.keyType(), body.key());
        if (body.name() == null || body.name().isBlank()) throw new IllegalArgumentException("Informe o nome de quem recebe (como no banco)");
        if (body.city() == null || body.city().isBlank()) throw new IllegalArgumentException("Informe a cidade de quem recebe");
        jdbc.update("UPDATE platform_settings SET pix_key_type = ?, pix_key = ?, pix_name = ?, pix_city = ?, updated_at = now()",
            body.keyType().name(), key, PixCode.ascii(body.name(), 25, ""), PixCode.ascii(body.city(), 15, ""));
        return pixSettings();
    }

    // ================= Clínica =================

    @GetMapping("/api/v1/subscription")
    public MySubscription mine(Authentication auth) {
        UUID clinicId = current.require(auth).clinicId();
        Plan plan = plan(clinicId);
        List<ChargeView> charges = jdbc.query(SELECT + """
                 WHERE s.clinic_id = ? AND s.status <> 'CANCELED'
                 ORDER BY CASE WHEN s.status IN ('OPEN', 'INFORMED') THEN 0 ELSE 1 END, s.due_on DESC LIMIT 24""",
            (rs, n) -> map(rs), clinicId);
        return new MySubscription(plan, pixSettings().key() != null, charges);
    }

    /** QR Code Pix da plataforma com o valor da mensalidade (só mensalidade a pagar da própria clínica). */
    @GetMapping("/api/v1/subscription/charges/{id}/pix")
    public PixCharge pix(Authentication auth, @PathVariable UUID id) {
        ChargeView charge = mineCharge(current.require(auth).clinicId(), id);
        if (!"OPEN".equals(charge.status())) throw new IllegalArgumentException("Esta mensalidade não está a pagar");
        PixSettings settings = pixSettings();
        if (settings.key() == null) throw new IllegalArgumentException("Pix da PhysioEvo ainda não configurado. Fale com o suporte.");
        String code = PixCode.build(settings.key(), settings.name(), settings.city(), charge.amount(),
            "PEVO" + id.toString().replace("-", "").substring(0, 12));
        return new PixCharge(code, PixCode.qrSvg(code), charge.amount(), settings.name());
    }

    /** "Pagamento realizado": fica pendente (para a clínica e para a plataforma) até a plataforma confirmar. */
    @PostMapping("/api/v1/subscription/charges/{id}/informed")
    @Transactional
    public ChargeView informed(Authentication auth, @PathVariable UUID id) {
        var owner = current.requireOwner(auth);
        jdbc.query("SELECT id FROM subscription_charges WHERE id = ? FOR UPDATE", (rs, n) -> 0, id);
        ChargeView charge = mineCharge(owner.clinicId(), id);
        if (!"OPEN".equals(charge.status())) throw new IllegalArgumentException("Esta mensalidade não está a pagar");
        jdbc.update("""
            UPDATE subscription_charges SET status = 'INFORMED', informed_at = now(), informed_by = ?, updated_at = now()
             WHERE id = ? AND clinic_id = ?""", owner.email(), id, owner.clinicId());
        return find(id);
    }

    // ================= Apoio =================

    private ChargeView mineCharge(UUID clinicId, UUID id) {
        return jdbc.query(SELECT + " WHERE s.id = ? AND s.clinic_id = ?", (rs, n) -> map(rs), id, clinicId)
            .stream().findFirst().orElseThrow(NoSuchElementException::new);
    }

    private ChargeView find(UUID id) {
        return jdbc.query(SELECT + " WHERE s.id = ?", (rs, n) -> map(rs), id).stream().findFirst()
            .orElseThrow(NoSuchElementException::new);
    }

    private ChargeView lock(UUID id) {
        jdbc.query("SELECT id FROM subscription_charges WHERE id = ? FOR UPDATE", (rs, n) -> 0, id);
        return find(id);
    }

    /** Renova até o vencimento seguinte (nunca diminui o prazo) e deixa a clínica como assinante. */
    private void renew(Authentication auth, ChargeView charge) {
        ClinicAccess.Access before = access.of(charge.clinicId());
        if ("BLOCKED".equals(before.status())) return; // bloqueio é decisão separada
        LocalDate until = charge.dueOn().plusMonths(1);
        if (before.accessUntil() != null && before.accessUntil().isAfter(until)) until = before.accessUntil();
        if ("ACTIVE".equals(before.status()) && before.accessUntil() == null) until = null; // assinante sem prazo continua sem prazo
        jdbc.update("""
            UPDATE clinics SET status = 'ACTIVE', access_until = ?, status_reason = ?, status_changed_at = now() WHERE id = ?""",
            until, "Mensalidade paga: " + charge.description(), charge.clinicId());
        jdbc.update("""
            INSERT INTO clinic_access_log(id, clinic_id, admin_email, action, status_before, status_after, access_until, reason)
            VALUES (?, ?, ?, 'PAYMENT', ?, 'ACTIVE', ?, ?)""",
            UUID.randomUUID(), charge.clinicId(), auth.getName(), before.effective(), until, "Mensalidade paga: " + charge.description());
    }

    private void requireNoChargeFor(UUID clinicId, LocalDate month) {
        Integer n = jdbc.queryForObject("""
            SELECT count(*) FROM subscription_charges WHERE clinic_id = ? AND reference_month = ? AND status <> 'CANCELED'""",
            Integer.class, clinicId, month);
        if (n != null && n > 0) throw new IllegalArgumentException("Já existe mensalidade deste mês para esta clínica");
    }

    private Total total(String where, String column, Object... args) {
        return jdbc.queryForObject("SELECT count(*), coalesce(sum(" + column + "), 0) FROM subscription_charges WHERE " + where,
            (rs, n) -> new Total(rs.getInt(1), rs.getBigDecimal(2)), args);
    }

    static YearMonth parseMonth(String month) {
        try {
            return YearMonth.parse(month);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Mês inválido");
        }
    }

    /** "Mensalidade de outubro/2026". */
    static String monthLabel(LocalDate month) {
        return "Mensalidade de " + month.getMonth().getDisplayName(TextStyle.FULL, BR) + "/" + month.getYear();
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
