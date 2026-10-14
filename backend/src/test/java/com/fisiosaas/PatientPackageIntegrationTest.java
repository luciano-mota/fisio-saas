package com.fisiosaas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class PatientPackageIntegrationTest extends IntegrationTest {
    String maria;
    String pilates;
    String dezSessoes;
    String tresSessoes;

    @BeforeEach
    void cadastros() throws Exception {
        maria = create("/api/v1/patients", OWNER_A, "{\"fullName\":\"Maria Souza\",\"whatsapp\":\"98999991234\"}");
        pilates = create("/api/v1/services", OWNER_A, "{\"name\":\"Pilates\",\"durationMinutes\":50}");
        dezSessoes = create("/api/v1/packages", OWNER_A, """
            {"serviceId":"%s","name":"10 sessões","sessionCount":10,"price":800.00,"validityDays":90}""".formatted(pilates));
        tresSessoes = create("/api/v1/packages", OWNER_A, """
            {"serviceId":"%s","name":"3 sessões","sessionCount":3}""".formatted(pilates));
    }

    private ResultActions send(String method, String url, String owner, String body) throws Exception {
        var request = switch (method) {
            case "PUT" -> put(url);
            case "DELETE" -> delete(url);
            default -> post(url);
        };
        return mvc.perform(request.with(user(owner)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body == null ? "" : body));
    }

    private String vender(String patient, String catalogPackage) throws Exception {
        return create("/api/v1/patients/" + patient + "/packages", OWNER_A,
            "{\"packageId\":\"%s\",\"purchasedOn\":\"2020-03-01\"}".formatted(catalogPackage));
    }

    private String sessao(String patient, String startsAt) throws Exception {
        return create("/api/v1/appointments", OWNER_A, """
            {"patientId":"%s","serviceId":"%s","startsAt":"%s"}""".formatted(patient, pilates, startsAt));
    }

    private ResultActions mudarStatus(String appointment, String status) throws Exception {
        return send("POST", "/api/v1/appointments/" + appointment + "/status", OWNER_A, "{\"status\":\"" + status + "\"}");
    }

    private JsonNode saldo(String patientPackage) throws Exception {
        String body = mvc.perform(get("/api/v1/patients/" + maria + "/packages").with(user(OWNER_A)))
            .andReturn().getResponse().getContentAsString();
        for (JsonNode node : json.readTree(body)) if (node.get("id").asText().equals(patientPackage)) return node;
        throw new AssertionError("pacote não listado");
    }

    private String pacoteDaSessao(String appointment) {
        return jdbc.queryForObject("SELECT patient_package_id::text FROM appointments WHERE id = ?::uuid",
            String.class, appointment);
    }

    @Test
    void vendaCopiaOCatalogoECalculaValidadeSemMudarComOCatalogo() throws Exception {
        send("POST", "/api/v1/patients/" + maria + "/packages", OWNER_A,
                "{\"packageId\":\"%s\",\"purchasedOn\":\"2026-01-10\",\"notes\":\"Pago metade\"}".formatted(dezSessoes))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("10 sessões"))
            .andExpect(jsonPath("$.serviceName").value("Pilates"))
            .andExpect(jsonPath("$.sessionCount").value(10))
            .andExpect(jsonPath("$.price").value(800.0))
            .andExpect(jsonPath("$.purchasedOn").value("2026-01-10"))
            .andExpect(jsonPath("$.expiresOn").value("2026-04-10"))
            .andExpect(jsonPath("$.used").value(0))
            .andExpect(jsonPath("$.remaining").value(10))
            .andExpect(jsonPath("$.lowBalance").value(false))
            .andExpect(jsonPath("$.notes").value("Pago metade"));

        // Catálogo muda depois: a venda não muda.
        send("PUT", "/api/v1/packages/" + dezSessoes, OWNER_A, """
            {"serviceId":"%s","name":"10 sessões","sessionCount":12,"price":900.00}""".formatted(pilates))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/patients/" + maria + "/packages").with(user(OWNER_A)))
            .andExpect(jsonPath("$[0].sessionCount").value(10))
            .andExpect(jsonPath("$[0].price").value(800.0));

        // Preço combinado na venda (desconto) vale sobre o do catálogo.
        send("POST", "/api/v1/patients/" + maria + "/packages", OWNER_A,
                "{\"packageId\":\"%s\",\"price\":700.00}".formatted(dezSessoes))
            .andExpect(jsonPath("$.price").value(700.0));
    }

    @Test
    void realizadaConsomeEAvisaQuandoFaltamDuas() throws Exception {
        String pacote = vender(maria, tresSessoes);
        String s1 = sessao(maria, "2020-03-02T08:00");
        sessao(maria, "2020-03-04T08:00");
        assertThat(pacoteDaSessao(s1)).isEqualTo(pacote);
        assertThat(saldo(pacote).get("scheduled").asInt()).isEqualTo(2);
        assertThat(saldo(pacote).get("remaining").asInt()).isEqualTo(3);
        assertThat(saldo(pacote).get("lowBalance").asBoolean()).isFalse();

        mudarStatus(s1, "DONE")
            .andExpect(jsonPath("$.countsAgainstPackage").value(true))
            .andExpect(jsonPath("$.packageName").value("3 sessões"))
            .andExpect(jsonPath("$.packageRemaining").value(2));
        JsonNode depois = saldo(pacote);
        assertThat(depois.get("used").asInt()).isEqualTo(1);
        assertThat(depois.get("scheduled").asInt()).isEqualTo(1);
        assertThat(depois.get("remaining").asInt()).isEqualTo(2);
        assertThat(depois.get("lowBalance").asBoolean()).isTrue();

        mvc.perform(get("/api/v1/patient-packages/low-balance").with(user(OWNER_A)))
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].patientName").value("Maria Souza"))
            .andExpect(jsonPath("$[0].remaining").value(2));
    }

    @Test
    void naoReservaMaisSessoesDoQueOPacoteTem() throws Exception {
        String pacote = vender(maria, tresSessoes);
        String body = send("POST", "/api/v1/appointments/series", OWNER_A, """
                {"patientId":"%s","serviceId":"%s","startDate":"2030-01-07","startTime":"08:00",
                 "weekdays":[1,3,5],"occurrences":5}""".formatted(maria, pilates))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode created = json.readTree(body).get("created");
        assertThat(created.get(0).get("patientPackageId").asText()).isEqualTo(pacote);
        assertThat(created.get(2).get("patientPackageId").asText()).isEqualTo(pacote);
        assertThat(created.get(3).get("patientPackageId").isNull()).isTrue();
        assertThat(created.get(4).get("patientPackageId").isNull()).isTrue();
        assertThat(saldo(pacote).get("scheduled").asInt()).isEqualTo(3);

        // Cancelar uma reservada libera a vaga para a próxima sessão nova.
        send("DELETE", "/api/v1/appointments/" + created.get(0).get("id").asText(), OWNER_A, null)
            .andExpect(status().isNoContent());
        assertThat(saldo(pacote).get("scheduled").asInt()).isEqualTo(2);
        String nova = sessao(maria, "2030-02-01T08:00");
        assertThat(pacoteDaSessao(nova)).isEqualTo(pacote);
    }

    @Test
    void faltaSegueARegraDaClinicaEMudarARegraNaoReescreveOPassado() throws Exception {
        String pacote = vender(maria, dezSessoes);
        String primeira = sessao(maria, "2020-03-02T08:00");
        String segunda = sessao(maria, "2020-03-04T08:00");

        // Padrão: falta não consome e solta o pacote.
        mvc.perform(get("/api/v1/clinic").with(user(OWNER_A))).andExpect(jsonPath("$.noShowConsumesSession").value(false));
        mudarStatus(primeira, "NO_SHOW").andExpect(jsonPath("$.countsAgainstPackage").value(false))
            .andExpect(jsonPath("$.patientPackageId").doesNotExist());
        assertThat(saldo(pacote).get("used").asInt()).isZero();

        send("PUT", "/api/v1/clinic", OWNER_A, "{\"displayName\":\"Fisio Ana\",\"noShowConsumesSession\":true}")
            .andExpect(jsonPath("$.noShowConsumesSession").value(true));
        mudarStatus(segunda, "NO_SHOW").andExpect(jsonPath("$.countsAgainstPackage").value(true));
        assertThat(saldo(pacote).get("used").asInt()).isEqualTo(1);

        // Voltar a regra não muda a falta que já consumiu.
        send("PUT", "/api/v1/clinic", OWNER_A, "{\"displayName\":\"Fisio Ana\",\"noShowConsumesSession\":false}");
        assertThat(saldo(pacote).get("used").asInt()).isEqualTo(1);
        // Salvar a clínica sem mandar o campo mantém a regra.
        send("PUT", "/api/v1/clinic", OWNER_A, "{\"displayName\":\"Fisio Ana\"}")
            .andExpect(jsonPath("$.noShowConsumesSession").value(false));
    }

    @Test
    void desfazerRealizadaDevolveASessaoEAvulsaNaoUsaPacote() throws Exception {
        String pacote = vender(maria, dezSessoes);
        String sessaoId = sessao(maria, "2020-03-02T08:00");
        mudarStatus(sessaoId, "DONE");
        assertThat(saldo(pacote).get("used").asInt()).isEqualTo(1);
        mudarStatus(sessaoId, "CONFIRMED").andExpect(jsonPath("$.countsAgainstPackage").value(false));
        assertThat(saldo(pacote).get("used").asInt()).isZero();
        assertThat(saldo(pacote).get("scheduled").asInt()).isEqualTo(1);

        // Avulsa por escolha: realizada não consome pacote.
        send("PUT", "/api/v1/appointments/" + sessaoId + "/package", OWNER_A, "{\"patientPackageId\":null}")
            .andExpect(jsonPath("$.patientPackageId").doesNotExist());
        mudarStatus(sessaoId, "DONE");
        // Ao marcar realizada sem pacote, usa o pacote disponível.
        assertThat(saldo(pacote).get("used").asInt()).isEqualTo(1);
        send("PUT", "/api/v1/appointments/" + sessaoId + "/package", OWNER_A, "{\"patientPackageId\":null}");
        assertThat(saldo(pacote).get("used").asInt()).isZero();
        send("PUT", "/api/v1/appointments/" + sessaoId + "/package", OWNER_A,
                "{\"patientPackageId\":\"%s\"}".formatted(pacote))
            .andExpect(jsonPath("$.countsAgainstPackage").value(true));
    }

    @Test
    void vendaDepoisDeAgendarPassaAsSessoesMarcadasParaOPacote() throws Exception {
        String a = sessao(maria, "2030-01-07T08:00");
        String b = sessao(maria, "2030-01-09T08:00");
        assertThat(pacoteDaSessao(a)).isNull();
        String pacote = vender(maria, dezSessoes);
        assertThat(pacoteDaSessao(a)).isEqualTo(pacote);
        assertThat(pacoteDaSessao(b)).isEqualTo(pacote);
    }

    @Test
    void desativarPacoteSoltaAgendadasEMantemRealizadas() throws Exception {
        String pacote = vender(maria, dezSessoes);
        String feita = sessao(maria, "2020-03-02T08:00");
        String futura = sessao(maria, "2030-01-07T08:00");
        mudarStatus(feita, "DONE");
        send("DELETE", "/api/v1/patient-packages/" + pacote, OWNER_A, null).andExpect(status().isNoContent());
        assertThat(pacoteDaSessao(feita)).isEqualTo(pacote);
        assertThat(pacoteDaSessao(futura)).isNull();
        JsonNode inativo = saldo(pacote);
        assertThat(inativo.get("active").asBoolean()).isFalse();
        assertThat(inativo.get("used").asInt()).isEqualTo(1);
        assertThat(inativo.get("lowBalance").asBoolean()).isFalse();
    }

    @Test
    void clinicaNaoVeNemVendeNemUsaPacoteDeOutraClinica() throws Exception {
        String pacoteA = vender(maria, dezSessoes);
        String joaoB = create("/api/v1/patients", OWNER_B, "{\"fullName\":\"João\"}");
        String rpgB = create("/api/v1/services", OWNER_B, "{\"name\":\"RPG\"}");
        String catalogoB = create("/api/v1/packages", OWNER_B, """
            {"serviceId":"%s","name":"5 sessões","sessionCount":5}""".formatted(rpgB));

        mvc.perform(get("/api/v1/patients/" + maria + "/packages").with(user(OWNER_B))).andExpect(status().isNotFound());
        send("POST", "/api/v1/patients/" + maria + "/packages", OWNER_B, "{\"packageId\":\"%s\"}".formatted(catalogoB))
            .andExpect(status().isNotFound());
        send("POST", "/api/v1/patients/" + joaoB + "/packages", OWNER_B, "{\"packageId\":\"%s\"}".formatted(dezSessoes))
            .andExpect(status().isBadRequest());
        send("PUT", "/api/v1/patient-packages/" + pacoteA, OWNER_B, "{\"price\":1}").andExpect(status().isNotFound());
        send("DELETE", "/api/v1/patient-packages/" + pacoteA, OWNER_B, null).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/patient-packages/low-balance").with(user(OWNER_B))).andExpect(jsonPath("$", hasSize(0)));

        String sessaoB = create("/api/v1/appointments", OWNER_B, """
            {"patientId":"%s","serviceId":"%s","startsAt":"2030-01-07T08:00"}""".formatted(joaoB, rpgB));
        send("PUT", "/api/v1/appointments/" + sessaoB + "/package", OWNER_B, "{\"patientPackageId\":\"%s\"}".formatted(pacoteA))
            .andExpect(status().isBadRequest());

        assertThat(saldo(pacoteA).get("price").asDouble()).isEqualTo(800.0);
        assertThat(saldo(pacoteA).get("active").asBoolean()).isTrue();
    }

    @Test
    void pacoteDeOutroPacienteNaoServe() throws Exception {
        String ana = create("/api/v1/patients", OWNER_A, "{\"fullName\":\"Ana\"}");
        String pacoteMaria = vender(maria, dezSessoes);
        String sessaoAna = sessao(ana, "2030-01-07T08:00");
        assertThat(pacoteDaSessao(sessaoAna)).isNull();
        send("PUT", "/api/v1/appointments/" + sessaoAna + "/package", OWNER_A,
                "{\"patientPackageId\":\"%s\"}".formatted(pacoteMaria))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Pacote não é deste paciente e serviço"));
        // O banco também recusa (chave estrangeira com paciente e serviço).
        assertThatThrownBy(() -> jdbc.update("UPDATE appointments SET patient_package_id = ?::uuid WHERE id = ?::uuid",
                pacoteMaria, sessaoAna))
            .hasMessageContaining("appointments_patient_package_fk");
    }

    @Test
    void colaboradoraVeSaldoMasNaoVende() throws Exception {
        String pacote = vender(maria, dezSessoes);
        mvc.perform(get("/api/v1/patients/" + maria + "/packages").with(user(STAFF_A)))
            .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        send("POST", "/api/v1/patients/" + maria + "/packages", STAFF_A, "{\"packageId\":\"%s\"}".formatted(dezSessoes))
            .andExpect(status().isForbidden());
        send("DELETE", "/api/v1/patient-packages/" + pacote, STAFF_A, null).andExpect(status().isForbidden());
        send("PUT", "/api/v1/clinic", STAFF_A, "{\"displayName\":\"X\",\"noShowConsumesSession\":true}")
            .andExpect(status().isForbidden());
    }
}
