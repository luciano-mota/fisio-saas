package com.fisiosaas.finance;

import com.fisiosaas.identity.CurrentClinic;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Recibo para dedução no Imposto de Renda: quem recebeu (nome, CPF/CNPJ, CREFITO, endereço), quem pagou (nome e CPF),
 * o que foi pago (datas, descrição, valor) e o total por extenso. De um pagamento ou de um período (ex.: o ano).
 * O painel imprime ou salva em PDF pelo navegador (sem serviço externo). Pagamentos estornados não entram.
 */
@RestController
@RequestMapping("/api/v1")
public class ReceiptController {

    public record Provider(String name, String clinicName, String crefito, String document, String address) {}

    public record Payer(UUID id, String name, String cpf) {}

    public record Item(LocalDate paidOn, String description, String method, BigDecimal amount) {}

    /** {@code missing}: o que falta no cadastro para o recibo valer no IR. */
    public record Receipt(String number, LocalDate issuedOn, LocalDate from, LocalDate to, Provider provider, Payer payer,
                          List<Item> items, BigDecimal total, String totalInWords, List<String> missing) {}

    private static final String ITEMS = """
        SELECT y.paid_on, y.method, y.charged_amount,
               CASE WHEN pp.id IS NOT NULL THEN 'Pacote de ' || ps.name || ' (' || pp.name || ')'
                    WHEN a.id IS NOT NULL THEN 'Sessão de ' || s.name || ' em ' || to_char((a.starts_at AT TIME ZONE c.timezone), 'DD/MM/YYYY')
                    ELSE 'Atendimento de fisioterapia' END AS description
          FROM payments y
          JOIN clinics c ON c.id = y.clinic_id
          LEFT JOIN patient_packages pp ON pp.id = y.patient_package_id AND pp.clinic_id = y.clinic_id
          LEFT JOIN services ps ON ps.id = pp.service_id AND ps.clinic_id = pp.clinic_id
          LEFT JOIN appointments a ON a.id = y.appointment_id AND a.clinic_id = y.clinic_id
          LEFT JOIN services s ON s.id = a.service_id AND s.clinic_id = a.clinic_id
        """;
    private static final RowMapper<Item> ITEM = (rs, n) -> new Item(rs.getObject("paid_on", LocalDate.class),
        rs.getString("description"), rs.getString("method"), rs.getBigDecimal("charged_amount"));

    private final JdbcTemplate jdbc;
    private final CurrentClinic current;
    private final PaymentController payments;

    public ReceiptController(JdbcTemplate jdbc, CurrentClinic current, PaymentController payments) {
        this.jdbc = jdbc;
        this.current = current;
        this.payments = payments;
    }

    @GetMapping("/payments/{id}/receipt")
    public Receipt ofPayment(Authentication auth, @PathVariable UUID id) {
        UUID clinicId = current.require(auth).clinicId();
        PaymentController.PaymentView p = payments.find(clinicId, id);
        if (p.canceled()) throw new IllegalArgumentException("Pagamento estornado não gera recibo");
        List<Item> items = jdbc.query(ITEMS + " WHERE y.id = ? AND y.clinic_id = ?", ITEM, id, clinicId);
        return build(clinicId, p.patientId(), "R-" + id.toString().substring(0, 8).toUpperCase(), p.paidOn(), p.paidOn(), items);
    }

    /** Todos os pagamentos do paciente no período (até 1 ano e pouco, para o recibo anual do IR). */
    @GetMapping("/patients/{patientId}/receipt")
    public Receipt ofPeriod(Authentication auth, @PathVariable UUID patientId, @RequestParam LocalDate from,
                            @RequestParam LocalDate to) {
        UUID clinicId = current.require(auth).clinicId();
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 400) throw new IllegalArgumentException("Período inválido");
        List<Item> items = jdbc.query(ITEMS + """
                 WHERE y.clinic_id = ? AND y.patient_id = ? AND NOT y.canceled AND y.paid_on BETWEEN ? AND ?
                 ORDER BY y.paid_on, y.created_at""", ITEM, clinicId, patientId, from, to);
        String number = "R-" + patientId.toString().substring(0, 4).toUpperCase() + "-" + from.toString().replace("-", "")
            + "-" + to.toString().replace("-", "");
        Receipt receipt = build(clinicId, patientId, number, from, to, items);
        if (items.isEmpty()) throw new IllegalArgumentException("Nenhum pagamento deste paciente no período");
        return receipt;
    }

    private Receipt build(UUID clinicId, UUID patientId, String number, LocalDate from, LocalDate to, List<Item> items) {
        Payer payer = jdbc.query("SELECT id, full_name, cpf FROM patients WHERE id = ? AND clinic_id = ?",
                (rs, n) -> new Payer(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)), patientId, clinicId)
            .stream().findFirst().orElseThrow(NoSuchElementException::new);
        Provider provider = jdbc.queryForObject("""
                SELECT coalesce(professional_name, display_name), display_name, crefito, document, address
                  FROM clinics WHERE id = ?""",
            (rs, n) -> new Provider(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), clinicId);
        List<String> missing = new ArrayList<>();
        if (payer.cpf() == null) missing.add("CPF do paciente");
        if (provider.document() == null || provider.document().isBlank()) missing.add("CPF/CNPJ do profissional");
        if (provider.crefito() == null || provider.crefito().isBlank()) missing.add("CREFITO");
        if (provider.address() == null || provider.address().isBlank()) missing.add("endereço do profissional");
        BigDecimal total = items.stream().map(Item::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Receipt(number, payments.today(clinicId), from, to, provider, payer, items, total,
            ValorPorExtenso.reais(total), missing);
    }
}
