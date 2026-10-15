package com.fisiosaas;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Pagamentos, maquininhas, quem está devendo e recebíveis, com isolamento entre clínicas. */
class FinanceIntegrationTest extends IntegrationTest {
    String pilates;
    String rpg;
    String maria;
    String machine;
    String sold;
    LocalDate today;

    private ResultActions send(MockHttpServletRequestBuilder request, String asUser, String body) throws Exception {
        return mvc.perform(request.with(user(asUser)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @BeforeEach
    void setUp() throws Exception {
        today = LocalDate.now(ZoneId.of("America/Fortaleza"));
        pilates = create("/api/v1/services", OWNER_A, "{\"name\":\"Pilates\",\"durationMinutes\":50,\"price\":90}");
        rpg = create("/api/v1/services", OWNER_A, "{\"name\":\"RPG\",\"durationMinutes\":50,\"price\":120}");
        maria = create("/api/v1/patients", OWNER_A, "{\"fullName\":\"Maria Souza\",\"whatsapp\":\"98988881111\"}");
        String catalog = create("/api/v1/packages", OWNER_A,
            "{\"serviceId\":\"" + pilates + "\",\"name\":\"10 sessões\",\"sessionCount\":10,\"price\":800}");
        sold = create("/api/v1/patients/" + maria + "/packages", OWNER_A,
            "{\"packageId\":\"" + catalog + "\",\"purchasedOn\":\"" + today + "\",\"price\":800}");
        machine = create("/api/v1/card-machines", OWNER_A, """
            {"name":"Maquininha X","debitFee":1.99,"creditFee":3.49,"creditDays":30,
             "installmentFees":[{"installments":3,"fee":4.99}]}""");
    }

    private String doneAvulsa() throws Exception {
        LocalDateTime start = LocalDateTime.now(ZoneId.of("America/Fortaleza")).minusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0);
        String session = create("/api/v1/appointments", OWNER_A,
            "{\"patientId\":\"" + maria + "\",\"serviceId\":\"" + rpg + "\",\"startsAt\":\"" + start + "\"}");
        send(post("/api/v1/appointments/" + session + "/status"), OWNER_A, "{\"status\":\"DONE\"}").andExpect(status().isOk());
        return session;
    }

    @Test
    void pacoteEAvulsaViramCobrancaEPagamentosAbatem() throws Exception {
        mvc.perform(get("/api/v1/finance/debtors").with(user(OWNER_A)))
            .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].balance").value(800.0));

        send(post("/api/v1/payments"), OWNER_A, """
            {"patientId":"%s","patientPackageId":"%s","method":"PIX","amount":300}""".formatted(maria, sold))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.netAmount").value(300.0)).andExpect(jsonPath("$.packageName").value("10 sessões"));
        String session = doneAvulsa();

        mvc.perform(get("/api/v1/patients/" + maria + "/finance").with(user(OWNER_A)))
            .andExpect(jsonPath("$.charged").value(920.0))
            .andExpect(jsonPath("$.paid").value(300.0))
            .andExpect(jsonPath("$.balance").value(620.0))
            .andExpect(jsonPath("$.charges", hasSize(2)))
            .andExpect(jsonPath("$.charges[?(@.kind == 'PACOTE')].paid").value(300.0));

        // Desconto na sessão avulsa.
        send(put("/api/v1/appointments/" + session + "/price"), OWNER_A, "{\"price\":100}").andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/finance/debtors").with(user(OWNER_A))).andExpect(jsonPath("$[0].balance").value(600.0));

        // A agenda mostra o valor da sessão avulsa e quanto já foi pago dela.
        send(post("/api/v1/payments"), OWNER_A, """
            {"patientId":"%s","appointmentId":"%s","method":"DINHEIRO","amount":40}""".formatted(maria, session))
            .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/appointments/" + session).with(user(OWNER_A)))
            .andExpect(jsonPath("$.price").value(100.0)).andExpect(jsonPath("$.paidAmount").value(40.0));
    }

    @Test
    void cartaoCalculaTaxaLiquidoERecebiveis() throws Exception {
        send(post("/api/v1/payments/preview"), OWNER_A, """
            {"method":"CREDITO","machineId":"%s","installments":3,"amount":600}""".formatted(machine))
            .andExpect(jsonPath("$.feeAmount").value(29.94)).andExpect(jsonPath("$.netAmount").value(570.06));

        String id = create("/api/v1/payments", OWNER_A, """
            {"patientId":"%s","patientPackageId":"%s","method":"CREDITO","machineId":"%s","installments":3,"amount":600}"""
            .formatted(maria, sold, machine));
        mvc.perform(get("/api/v1/finance/receivables").with(user(OWNER_A)))
            .andExpect(jsonPath("$", hasSize(3)))
            .andExpect(jsonPath("$[0].dueOn").value(today.plusDays(30).toString()))
            .andExpect(jsonPath("$[0].machineName").value("Maquininha X"));
        String month = YearMonth.from(today).toString();
        mvc.perform(get("/api/v1/finance/summary?month=" + month).with(user(OWNER_A)))
            .andExpect(jsonPath("$.received").value(600.0))
            .andExpect(jsonPath("$.fees").value(29.94))
            .andExpect(jsonPath("$.net").value(570.06))
            .andExpect(jsonPath("$.toReceive").value(570.06))
            .andExpect(jsonPath("$.open").value(200.0));

        String receivable = json.readTree(mvc.perform(get("/api/v1/finance/receivables").with(user(OWNER_A)))
            .andReturn().getResponse().getContentAsString()).get(0).get("id").asText();
        mvc.perform(post("/api/v1/finance/receivables/" + receivable + "/received").with(user(OWNER_A)).with(csrf()))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/finance/receivables").with(user(OWNER_A))).andExpect(jsonPath("$", hasSize(2)));

        // Estorno: volta a dever e sai do "a receber"; não estorna duas vezes.
        mvc.perform(post("/api/v1/payments/" + id + "/cancel").with(user(OWNER_A)).with(csrf()))
            .andExpect(jsonPath("$.canceled").value(true));
        mvc.perform(post("/api/v1/payments/" + id + "/cancel").with(user(OWNER_A)).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/finance/debtors").with(user(OWNER_A))).andExpect(jsonPath("$[0].balance").value(800.0));
        mvc.perform(get("/api/v1/finance/receivables").with(user(OWNER_A))).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void repasseDaTaxaCobraMaisEAbateOValor() throws Exception {
        send(put("/api/v1/clinic"), OWNER_A, "{\"displayName\":\"Fisio Ana\",\"publicPageEnabled\":true,\"passCardFee\":true}")
            .andExpect(jsonPath("$.passCardFee").value(true));
        send(post("/api/v1/payments"), OWNER_A, """
            {"patientId":"%s","method":"CREDITO","machineId":"%s","installments":3,"amount":600}""".formatted(maria, machine))
            .andExpect(jsonPath("$.chargedAmount").value(631.51))
            .andExpect(jsonPath("$.netAmount").value(600.0))
            .andExpect(jsonPath("$.amount").value(600.0));
        mvc.perform(get("/api/v1/finance/debtors").with(user(OWNER_A))).andExpect(jsonPath("$[0].balance").value(200.0));
    }

    @Test
    void validaFormaDePagamento() throws Exception {
        send(post("/api/v1/payments"), OWNER_A, "{\"patientId\":\"%s\",\"method\":\"CREDITO\",\"amount\":100}".formatted(maria))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Escolha a maquininha"));
        send(post("/api/v1/payments"), OWNER_A, "{\"patientId\":\"%s\",\"method\":\"PIX\",\"installments\":3,\"amount\":100}".formatted(maria))
            .andExpect(status().isBadRequest());
        send(post("/api/v1/payments"), OWNER_A, "{\"patientId\":\"%s\",\"method\":\"PIX\",\"amount\":0}".formatted(maria))
            .andExpect(status().isBadRequest());
        send(post("/api/v1/payments"), OWNER_A, "{\"patientId\":\"%s\",\"method\":\"BOLETO\",\"amount\":10}".formatted(maria))
            .andExpect(status().isBadRequest());
        // Maquininha desativada não serve.
        mvc.perform(delete("/api/v1/card-machines/" + machine).with(user(OWNER_A)).with(csrf())).andExpect(status().isNoContent());
        send(post("/api/v1/payments"), OWNER_A, """
            {"patientId":"%s","method":"DEBITO","machineId":"%s","amount":90}""".formatted(maria, machine))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Maquininha desativada"));
    }

    @Test
    void cadaClinicaSoVeEMexeNoProprioFinanceiro() throws Exception {
        String pix = create("/api/v1/payments", OWNER_A, "{\"patientId\":\"%s\",\"method\":\"PIX\",\"amount\":50}".formatted(maria));
        String paulo = create("/api/v1/patients", OWNER_B, "{\"fullName\":\"Paulo\"}");
        String machineB = create("/api/v1/card-machines", OWNER_B, "{\"name\":\"B\",\"debitFee\":2,\"creditFee\":3}");

        mvc.perform(get("/api/v1/finance/debtors").with(user(OWNER_B))).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/v1/patients/" + maria + "/finance").with(user(OWNER_B))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/card-machines").with(user(OWNER_B))).andExpect(jsonPath("$", hasSize(1)));
        send(put("/api/v1/card-machines/" + machine), OWNER_B, "{\"name\":\"x\",\"debitFee\":0,\"creditFee\":0}").andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/card-machines/" + machine).with(user(OWNER_B)).with(csrf())).andExpect(status().isNotFound());
        // Pagar paciente de outra clínica, usar maquininha ou pacote de outra clínica/paciente.
        send(post("/api/v1/payments"), OWNER_B, "{\"patientId\":\"%s\",\"method\":\"PIX\",\"amount\":10}".formatted(maria))
            .andExpect(status().isNotFound());
        send(post("/api/v1/payments"), OWNER_B, """
            {"patientId":"%s","method":"DEBITO","machineId":"%s","amount":10}""".formatted(paulo, machine)).andExpect(status().isNotFound());
        send(post("/api/v1/payments"), OWNER_A, """
            {"patientId":"%s","method":"DEBITO","machineId":"%s","amount":10}""".formatted(maria, machineB)).andExpect(status().isNotFound());
        send(post("/api/v1/payments"), OWNER_B, """
            {"patientId":"%s","patientPackageId":"%s","method":"PIX","amount":10}""".formatted(paulo, sold)).andExpect(status().isBadRequest());
        String ana = create("/api/v1/patients", OWNER_A, "{\"fullName\":\"Ana\"}");
        send(post("/api/v1/payments"), OWNER_A, """
            {"patientId":"%s","patientPackageId":"%s","method":"PIX","amount":10}""".formatted(ana, sold))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Pacote não é deste paciente"));
        mvc.perform(post("/api/v1/payments/" + pix + "/cancel").with(user(OWNER_B)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/payments?from=" + today.minusDays(1) + "&to=" + today.plusDays(1)).with(user(OWNER_B)))
            .andExpect(jsonPath("$", hasSize(0)));
        String session = doneAvulsa();
        send(put("/api/v1/appointments/" + session + "/price"), OWNER_B, "{\"price\":0}").andExpect(status().isNotFound());
    }

    @Test
    void colaboradoraConsultaMasNaoLanca() throws Exception {
        mvc.perform(get("/api/v1/finance/debtors").with(user(STAFF_A))).andExpect(jsonPath("$", hasSize(1)));
        send(post("/api/v1/payments"), STAFF_A, "{\"patientId\":\"%s\",\"method\":\"PIX\",\"amount\":50}".formatted(maria))
            .andExpect(status().isForbidden());
        send(post("/api/v1/card-machines"), STAFF_A, "{\"name\":\"x\",\"debitFee\":0,\"creditFee\":0}").andExpect(status().isForbidden());
    }
}
