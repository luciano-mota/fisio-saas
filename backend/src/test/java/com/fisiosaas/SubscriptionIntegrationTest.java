package com.fisiosaas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Financeiro dos assinantes: plano por clínica, mensalidades, Pix da plataforma, "pagamento realizado" (pendente
 * para os dois lados) e confirmação (receita da plataforma, despesa da clínica).
 */
class SubscriptionIntegrationTest extends IntegrationTest {
    static final RequestPostProcessor ADMIN = user("equipe@physioevo.test").roles("PLATFORM_ADMIN");
    String month;

    @BeforeEach
    void setUp() throws Exception {
        month = YearMonth.now(ZoneId.of("America/Fortaleza")).toString();
        jdbc.update("UPDATE platform_settings SET pix_key_type = NULL, pix_key = NULL, pix_name = NULL, pix_city = NULL");
    }

    private ResultActions send(MockHttpServletRequestBuilder req, RequestPostProcessor who, String body) throws Exception {
        return mvc.perform(req.with(who).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private LocalDate today() {
        return LocalDate.now(ZoneId.of("America/Fortaleza"));
    }

    private void plan(java.util.UUID clinic, String price, int day) throws Exception {
        send(put("/api/v1/admin/clinics/" + clinic + "/plan"), ADMIN, "{\"planPrice\":" + price + ",\"billingDay\":" + day + "}")
            .andExpect(status().isOk());
    }

    private String generateAndGetId(java.util.UUID clinic) throws Exception {
        send(post("/api/v1/admin/finance/generate?month=" + month), ADMIN, "").andExpect(status().isOk());
        return jdbc.queryForObject("SELECT id::text FROM subscription_charges WHERE clinic_id = ? AND status <> 'CANCELED'",
            String.class, clinic);
    }

    private void configurePix() throws Exception {
        send(put("/api/v1/admin/settings/pix"), ADMIN, "{\"keyType\":\"EMAIL\",\"key\":\"Pix@PhysioEvo.com\",\"name\":\"PhysioEvo Tecnologia\",\"city\":\"São Luís\"}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.key").value("pix@physioevo.com"));
    }

    @Test
    void soAEquipeMexeNoFinanceiroDaPlataforma() throws Exception {
        mvc.perform(get("/api/v1/admin/finance?month=" + month).with(user(OWNER_A))).andExpect(status().isForbidden());
        send(put("/api/v1/admin/clinics/" + clinicA + "/plan"), user(OWNER_A), "{\"planPrice\":10}").andExpect(status().isForbidden());
        send(put("/api/v1/admin/settings/pix"), user(OWNER_A), "{}").andExpect(status().isForbidden());
    }

    @Test
    void planoPorClinicaEGeracaoSemDuplicarNemCobrarTesteOuGratis() throws Exception {
        plan(clinicA, "79.90", 5);
        plan(clinicB, "49.90", 15); // promoção
        jdbc.update("INSERT INTO clinics(id, slug, display_name, public_page_enabled, status, plan_price) VALUES (gen_random_uuid(), 'em-teste', 'Em teste', false, 'TRIAL', 99)");
        jdbc.update("INSERT INTO clinics(id, slug, display_name, public_page_enabled, status, plan_price) VALUES (gen_random_uuid(), 'gratis', 'Grátis', false, 'FREE', 99)");

        send(post("/api/v1/admin/finance/generate?month=" + month), ADMIN, "")
            .andExpect(jsonPath("$.created").value(2)).andExpect(jsonPath("$.skipped").value(0));
        send(post("/api/v1/admin/finance/generate?month=" + month), ADMIN, "")
            .andExpect(jsonPath("$.created").value(0)).andExpect(jsonPath("$.skipped").value(2));

        mvc.perform(get("/api/v1/admin/finance?month=" + month).with(ADMIN))
            .andExpect(jsonPath("$.mrr").value(129.80))
            .andExpect(jsonPath("$.expected.count").value(2))
            .andExpect(jsonPath("$.expected.amount").value(129.80))
            .andExpect(jsonPath("$.charges[?(@.clinicName=='Fisio Bia')].amount").value(49.90))
            .andExpect(jsonPath("$.charges[?(@.clinicName=='Fisio Bia')].dueOn").value(YearMonth.parse(month).atDay(15).toString()));
        // Cobrança manual do mesmo mês: recusada (já existe).
        send(post("/api/v1/admin/clinics/" + clinicA + "/charges"), ADMIN,
            "{\"referenceMonth\":\"" + month + "-01\",\"amount\":10,\"dueOn\":\"" + today() + "\"}")
            .andExpect(status().isBadRequest());
    }

    @Test
    void clinicaVeSoAsDelaComPixDaPlataformaEOutraClinicaNaoAcessa() throws Exception {
        plan(clinicA, "79.90", 10);
        plan(clinicB, "49.90", 10);
        String chargeA = generateAndGetId(clinicA);

        mvc.perform(get("/api/v1/subscription").with(user(STAFF_A)))
            .andExpect(jsonPath("$.plan.planPrice").value(79.90))
            .andExpect(jsonPath("$.pixAvailable").value(false))
            .andExpect(jsonPath("$.charges", hasSize(1)))
            .andExpect(jsonPath("$.charges[0].status").value("OPEN"));
        // Sem Pix configurado: mensagem clara.
        mvc.perform(get("/api/v1/subscription/charges/" + chargeA + "/pix").with(user(OWNER_A)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(containsString("ainda não configurado")));
        configurePix();
        mvc.perform(get("/api/v1/subscription/charges/" + chargeA + "/pix").with(user(OWNER_A)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(containsString("pix@physioevo.com")))
            .andExpect(jsonPath("$.code").value(containsString("540579.90")))
            .andExpect(jsonPath("$.qrSvg").value(containsString("<svg")))
            .andExpect(jsonPath("$.receiver").value("PHYSIOEVO TECNOLOGIA"));
        // Isolamento: a outra clínica não vê nem paga a mensalidade da Ana.
        mvc.perform(get("/api/v1/subscription/charges/" + chargeA + "/pix").with(user(OWNER_B))).andExpect(status().isNotFound());
        send(post("/api/v1/subscription/charges/" + chargeA + "/informed"), user(OWNER_B), "").andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/subscription").with(user(OWNER_B)))
            .andExpect(jsonPath("$.charges[0].amount").value(49.90)).andExpect(jsonPath("$.charges", hasSize(1)));
    }

    @Test
    void pagamentoInformadoFicaPendenteNosDoisLadosEConfirmadoViraReceitaEDespesa() throws Exception {
        plan(clinicA, "79.90", 10);
        String charge = generateAndGetId(clinicA);
        jdbc.update("UPDATE clinics SET access_until = ? WHERE id = ?", today().plusDays(2), clinicA);

        // Colaboradora não informa pagamento; a responsável sim.
        send(post("/api/v1/subscription/charges/" + charge + "/informed"), user(STAFF_A), "").andExpect(status().isForbidden());
        send(post("/api/v1/subscription/charges/" + charge + "/informed"), user(OWNER_A), "")
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INFORMED")).andExpect(jsonPath("$.informedBy").value(OWNER_A));
        send(post("/api/v1/subscription/charges/" + charge + "/informed"), user(OWNER_A), "").andExpect(status().isBadRequest());

        // Pendente: na clínica e na plataforma; ainda não é despesa nem receita.
        mvc.perform(get("/api/v1/finance/summary?month=" + month).with(user(OWNER_A)))
            .andExpect(jsonPath("$.expensesPending").value(79.90)).andExpect(jsonPath("$.expenses").value(0));
        mvc.perform(get("/api/v1/admin/finance?month=" + month).with(ADMIN))
            .andExpect(jsonPath("$.informed.count").value(1)).andExpect(jsonPath("$.informed.amount").value(79.90))
            .andExpect(jsonPath("$.received.amount").value(0))
            .andExpect(jsonPath("$.charges[0].status").value("INFORMED"));

        // A plataforma confirma: receita para ela, despesa para a clínica, acesso renovado até o próximo vencimento.
        send(post("/api/v1/admin/charges/" + charge + "/confirm"), ADMIN, "{\"method\":\"PIX\",\"notes\":\"Conferido no banco\"}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"))
            .andExpect(jsonPath("$.paidAmount").value(79.90)).andExpect(jsonPath("$.confirmedBy").value("equipe@physioevo.test"));
        mvc.perform(get("/api/v1/finance/summary?month=" + month).with(user(OWNER_A)))
            .andExpect(jsonPath("$.expenses").value(79.90)).andExpect(jsonPath("$.expensesPending").value(0));
        mvc.perform(get("/api/v1/admin/finance?month=" + month).with(ADMIN))
            .andExpect(jsonPath("$.received.count").value(1)).andExpect(jsonPath("$.received.amount").value(79.90))
            .andExpect(jsonPath("$.informed.count").value(0));
        LocalDate due = YearMonth.parse(month).atDay(10);
        LocalDate expected = due.plusMonths(1).isAfter(today().plusDays(2)) ? due.plusMonths(1) : today().plusDays(2);
        assertThat(jdbc.queryForObject("SELECT access_until FROM clinics WHERE id = ?", LocalDate.class, clinicA)).isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT action FROM clinic_access_log WHERE clinic_id = ?", String.class, clinicA)).isEqualTo("PAYMENT");
        // Confirmar de novo: não.
        send(post("/api/v1/admin/charges/" + charge + "/confirm"), ADMIN, "{}").andExpect(status().isBadRequest());
        // A outra clínica não tem despesa.
        mvc.perform(get("/api/v1/finance/summary?month=" + month).with(user(OWNER_B))).andExpect(jsonPath("$.expenses").value(0));
    }

    @Test
    void naoRecebiVoltaParaAPagarECancelarExigeMotivo() throws Exception {
        plan(clinicA, "79.90", 10);
        String charge = generateAndGetId(clinicA);
        send(post("/api/v1/subscription/charges/" + charge + "/informed"), user(OWNER_A), "").andExpect(status().isOk());
        send(post("/api/v1/admin/charges/" + charge + "/reject"), ADMIN, "{\"reason\":\"Não caiu na conta\"}")
            .andExpect(jsonPath("$.status").value("OPEN"))
            .andExpect(jsonPath("$.notes").value("Pagamento não identificado: Não caiu na conta"));
        send(post("/api/v1/admin/charges/" + charge + "/cancel"), ADMIN, "{\"reason\":\" \"}").andExpect(status().isBadRequest());
        send(post("/api/v1/admin/charges/" + charge + "/cancel"), ADMIN, "{\"reason\":\"Cortesia\"}")
            .andExpect(jsonPath("$.status").value("CANCELED"));
        mvc.perform(get("/api/v1/subscription").with(user(OWNER_A))).andExpect(jsonPath("$.charges", hasSize(0)));
        // Cancelada não impede gerar de novo o mês.
        send(post("/api/v1/admin/finance/generate?month=" + month), ADMIN, "").andExpect(jsonPath("$.created").value(1));
    }

    @Test
    void clinicaSuspensaConsegueVerOPixEInformarOPagamento() throws Exception {
        plan(clinicA, "79.90", 10);
        String charge = generateAndGetId(clinicA);
        configurePix();
        jdbc.update("UPDATE clinics SET status = 'SUSPENDED' WHERE id = ?", clinicA);
        mvc.perform(get("/api/v1/subscription/charges/" + charge + "/pix").with(user(OWNER_A))).andExpect(status().isOk());
        send(post("/api/v1/subscription/charges/" + charge + "/informed"), user(OWNER_A), "")
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INFORMED"));
        // Confirmar reativa a clínica (deixa de estar suspensa).
        send(post("/api/v1/admin/charges/" + charge + "/confirm"), ADMIN, "{}").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM clinics WHERE id = ?", String.class, clinicA)).isEqualTo("ACTIVE");
    }

    @Test
    void validaPlanoEConfirmacao() throws Exception {
        send(put("/api/v1/admin/clinics/" + clinicA + "/plan"), ADMIN, "{\"planPrice\":-1}").andExpect(status().isBadRequest());
        send(put("/api/v1/admin/clinics/" + clinicA + "/plan"), ADMIN, "{\"planPrice\":10,\"billingDay\":31}").andExpect(status().isBadRequest());
        send(put("/api/v1/admin/clinics/" + java.util.UUID.randomUUID() + "/plan"), ADMIN, "{\"planPrice\":10}").andExpect(status().isNotFound());
        plan(clinicA, "79.90", 10);
        String charge = generateAndGetId(clinicA);
        send(post("/api/v1/admin/charges/" + charge + "/confirm"), ADMIN, "{\"method\":\"CHEQUE\"}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Forma de pagamento inválida"));
        send(post("/api/v1/admin/charges/" + charge + "/confirm"), ADMIN, "{\"paidOn\":\"" + today().plusDays(1) + "\"}")
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/finance?month=13-2026").with(ADMIN)).andExpect(status().isBadRequest());
    }
}
