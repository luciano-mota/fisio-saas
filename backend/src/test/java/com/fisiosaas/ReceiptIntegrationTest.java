package com.fisiosaas;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Recibo para o IR: dados do profissional e do paciente, valor por extenso, período e isolamento entre clínicas. */
class ReceiptIntegrationTest extends IntegrationTest {
    String maria;
    String sold;
    LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        today = LocalDate.now(ZoneId.of("America/Fortaleza"));
        jdbc.update("UPDATE clinics SET professional_name = 'Ana Lima', crefito = '12345-F', document = '529.982.247-25', "
            + "address = 'Rua das Flores, 10 - São Luís/MA' WHERE id = ?", clinicA);
        String pilates = create("/api/v1/services", OWNER_A, "{\"name\":\"Pilates\",\"durationMinutes\":50,\"price\":90}");
        maria = create("/api/v1/patients", OWNER_A, "{\"fullName\":\"Maria Souza\",\"cpf\":\"16899535009\",\"whatsapp\":\"98988881111\"}");
        String catalog = create("/api/v1/packages", OWNER_A,
            "{\"serviceId\":\"" + pilates + "\",\"name\":\"10 sessões\",\"sessionCount\":10,\"price\":800}");
        sold = create("/api/v1/patients/" + maria + "/packages", OWNER_A,
            "{\"packageId\":\"" + catalog + "\",\"purchasedOn\":\"" + today + "\",\"price\":800}");
    }

    private String pay(String amount, LocalDate on) throws Exception {
        return create("/api/v1/payments", OWNER_A, """
            {"patientId":"%s","patientPackageId":"%s","method":"PIX","amount":%s,"paidOn":"%s"}""".formatted(maria, sold, amount, on));
    }

    @Test
    void reciboDeUmPagamento() throws Exception {
        String id = pay("320.50", today);
        mvc.perform(get("/api/v1/payments/" + id + "/receipt").with(user(OWNER_A)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.provider.name").value("Ana Lima"))
            .andExpect(jsonPath("$.provider.crefito").value("12345-F"))
            .andExpect(jsonPath("$.payer.name").value("Maria Souza"))
            .andExpect(jsonPath("$.payer.cpf").value("16899535009"))
            .andExpect(jsonPath("$.items[0].description").value("Pacote de Pilates (10 sessões)"))
            .andExpect(jsonPath("$.total").value(320.5))
            .andExpect(jsonPath("$.totalInWords").value("trezentos e vinte reais e cinquenta centavos"))
            .andExpect(jsonPath("$.missing", hasSize(0)));
        // Colaborador também emite; outra clínica não enxerga.
        mvc.perform(get("/api/v1/payments/" + id + "/receipt").with(user(STAFF_A))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/payments/" + id + "/receipt").with(user(OWNER_B))).andExpect(status().isNotFound());

        // Estornado não gera recibo.
        mvc.perform(post("/api/v1/payments/" + id + "/cancel").with(user(OWNER_A)).with(csrf())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/payments/" + id + "/receipt").with(user(OWNER_A)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Pagamento estornado não gera recibo"));
    }

    @Test
    void reciboDoPeriodoSomaOsPagamentosEAvisaOQueFalta() throws Exception {
        pay("300", today.minusDays(10));
        pay("200", today);
        String estornado = pay("50", today);
        mvc.perform(post("/api/v1/payments/" + estornado + "/cancel").with(user(OWNER_A)).with(csrf())).andExpect(status().isOk());
        String url = "/api/v1/patients/" + maria + "/receipt?from=" + today.minusDays(30) + "&to=" + today;

        mvc.perform(get(url).with(user(OWNER_A)))
            .andExpect(jsonPath("$.items", hasSize(2)))
            .andExpect(jsonPath("$.total").value(500.0))
            .andExpect(jsonPath("$.totalInWords").value("quinhentos reais"));
        mvc.perform(get(url).with(user(OWNER_B))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/patients/" + maria + "/receipt?from=2020-01-01&to=2020-12-31").with(user(OWNER_A)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Nenhum pagamento deste paciente no período"));

        // Sem CPF do paciente e sem CREFITO, o recibo avisa.
        jdbc.update("UPDATE patients SET cpf = NULL");
        jdbc.update("UPDATE clinics SET crefito = NULL WHERE id = ?", clinicA);
        mvc.perform(get(url).with(user(OWNER_A)))
            .andExpect(jsonPath("$.missing", hasItem("CPF do paciente")))
            .andExpect(jsonPath("$.missing", hasItem("CREFITO")));
    }
}
