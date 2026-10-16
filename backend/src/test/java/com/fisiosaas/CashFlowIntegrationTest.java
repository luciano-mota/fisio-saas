package com.fisiosaas;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Relatório de fluxo de caixa: entradas, taxas e saídas (assinatura), líquido com a despesa descontada; isolamento. */
class CashFlowIntegrationTest extends IntegrationTest {
    static final RequestPostProcessor ADMIN = user("equipe@physioevo.test").roles("PLATFORM_ADMIN");
    LocalDate today;
    String maria;

    @BeforeEach
    void setUp() throws Exception {
        today = LocalDate.now(ZoneId.of("America/Fortaleza"));
        String pilates = create("/api/v1/services", OWNER_A, "{\"name\":\"Pilates\",\"durationMinutes\":50,\"price\":90}");
        maria = create("/api/v1/patients", OWNER_A, "{\"cpf\":\"" + cpf() + "\",\"fullName\":\"Maria Souza\"}");
        String catalog = create("/api/v1/packages", OWNER_A,
            "{\"serviceId\":\"" + pilates + "\",\"name\":\"10 sessões\",\"sessionCount\":10,\"price\":800}");
        String sold = create("/api/v1/patients/" + maria + "/packages", OWNER_A,
            "{\"packageId\":\"" + catalog + "\",\"purchasedOn\":\"" + today + "\",\"price\":800}");
        String machine = create("/api/v1/card-machines", OWNER_A, "{\"name\":\"Maquininha\",\"debitFee\":2,\"creditFee\":4}");
        create("/api/v1/payments", OWNER_A, """
            {"patientId":"%s","patientPackageId":"%s","method":"PIX","amount":300,"paidOn":"%s"}""".formatted(maria, sold, today));
        create("/api/v1/payments", OWNER_A, """
            {"patientId":"%s","method":"DEBITO","machineId":"%s","amount":100,"paidOn":"%s"}""".formatted(maria, machine, today));
        // Mensalidade PhysioEvo paga hoje (despesa da clínica, receita da plataforma).
        jdbc.update("""
            INSERT INTO subscription_charges(id, clinic_id, reference_month, description, amount, due_on, status, paid_on, paid_amount,
                                             method, created_by)
            VALUES (?, ?, ?, 'Mensalidade de teste', 79.90, ?, 'PAID', ?, 79.90, 'PIX', 'equipe')""",
            UUID.randomUUID(), clinicA, today.withDayOfMonth(1), today, today);
    }

    private String range() {
        return "from=" + today.withDayOfMonth(1) + "&to=" + today;
    }

    @Test
    void entradasTaxasESaidasComLiquidoDescontandoAAssinatura() throws Exception {
        mvc.perform(get("/api/v1/finance/cashflow?" + range()).with(user(STAFF_A)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.clinic.name").value("Fisio Ana"))
            .andExpect(jsonPath("$.entries", hasSize(3)))
            .andExpect(jsonPath("$.entries[0].kind").value("IN"))
            .andExpect(jsonPath("$.entries[2].kind").value("OUT"))
            .andExpect(jsonPath("$.entries[2].party").value("PhysioEvo"))
            .andExpect(jsonPath("$.entries[?(@.method=='PIX' && @.kind=='IN')].description").value("Pacote de Pilates (10 sessões)"))
            .andExpect(jsonPath("$.totals.gross").value(400.0))
            .andExpect(jsonPath("$.totals.fees").value(2.0))
            .andExpect(jsonPath("$.totals.expenses").value(79.90))
            .andExpect(jsonPath("$.totals.net").value(318.10))
            .andExpect(jsonPath("$.byMethod", hasSize(2)));
        // O resumo do mês também desconta a assinatura do líquido.
        mvc.perform(get("/api/v1/finance/summary?month=" + today.toString().substring(0, 7)).with(user(OWNER_A)))
            .andExpect(jsonPath("$.net").value(398.0))
            .andExpect(jsonPath("$.expenses").value(79.90))
            .andExpect(jsonPath("$.result").value(318.10));
    }

    @Test
    void outraClinicaNaoVeOsLancamentos() throws Exception {
        mvc.perform(get("/api/v1/finance/cashflow?" + range()).with(user(OWNER_B)))
            .andExpect(jsonPath("$.clinic.name").value("Fisio Bia"))
            .andExpect(jsonPath("$.entries", hasSize(0)))
            .andExpect(jsonPath("$.totals.net").value(0));
    }

    @Test
    void periodoInvalido() throws Exception {
        mvc.perform(get("/api/v1/finance/cashflow?from=" + today + "&to=" + today.minusDays(1)).with(user(OWNER_A)))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/finance/cashflow?from=" + today.minusYears(2) + "&to=" + today).with(user(OWNER_A)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Escolha um período de até um ano"));
    }

    @Test
    void plataformaTemOProprioFluxoComAsMensalidadesRecebidas() throws Exception {
        mvc.perform(get("/api/v1/admin/finance/cashflow?" + range()).with(user(OWNER_A))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/finance/cashflow?" + range()).with(ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.clinic.name").value("PhysioEvo"))
            .andExpect(jsonPath("$.entries", hasSize(1)))
            .andExpect(jsonPath("$.entries[0].party").value("Fisio Ana"))
            .andExpect(jsonPath("$.totals.gross").value(79.90))
            .andExpect(jsonPath("$.totals.net").value(79.90));
        // Pagamentos de pacientes da clínica não aparecem no fluxo da plataforma.
        mvc.perform(post("/api/v1/admin/finance/cashflow").with(ADMIN).with(csrf()).contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().is4xxClientError());
    }
}
