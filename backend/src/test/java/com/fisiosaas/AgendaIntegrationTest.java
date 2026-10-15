package com.fisiosaas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class AgendaIntegrationTest extends IntegrationTest {
    // Segunda-feira no futuro (para agendar) e uma data passada (para marcar realizada/falta).
    static final String FUTURO = "2030-01-07";
    static final String PASSADO = "2020-03-02";

    String mariaA;
    String pilatesA;
    String joaoB;
    String rpgB;

    @BeforeEach
    void cadastros() throws Exception {
        mariaA = create("/api/v1/patients", OWNER_A, "{\"cpf\":\"" + cpf() + "\",\"fullName\":\"Maria Souza\",\"whatsapp\":\"98999991234\"}");
        pilatesA = create("/api/v1/services", OWNER_A, "{\"name\":\"Pilates\",\"durationMinutes\":50}");
        joaoB = create("/api/v1/patients", OWNER_B, "{\"cpf\":\"" + cpf() + "\",\"fullName\":\"João\"}");
        rpgB = create("/api/v1/services", OWNER_B, "{\"name\":\"RPG\"}");
    }

    private String sessao(String owner, String patient, String service, String startsAt) throws Exception {
        return create("/api/v1/appointments", owner, """
            {"patientId":"%s","serviceId":"%s","startsAt":"%s"}""".formatted(patient, service, startsAt));
    }

    private ResultActions postJson(String url, String owner, String body) throws Exception {
        return mvc.perform(post(url).with(user(owner)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions mudarStatus(String owner, String id, String status) throws Exception {
        return postJson("/api/v1/appointments/" + id + "/status", owner, "{\"status\":\"" + status + "\"}");
    }

    @Test
    void agendaSessaoComDuracaoDoServicoNoFusoDaClinica() throws Exception {
        String id = sessao(OWNER_A, mariaA, pilatesA, FUTURO + "T08:00");

        mvc.perform(get("/api/v1/appointments").param("from", FUTURO).param("to", FUTURO).with(user(OWNER_A)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].id").value(id))
            .andExpect(jsonPath("$[0].kind").value("SESSION"))
            .andExpect(jsonPath("$[0].patientName").value("Maria Souza"))
            .andExpect(jsonPath("$[0].patientWhatsapp").value("98999991234"))
            .andExpect(jsonPath("$[0].serviceName").value("Pilates"))
            .andExpect(jsonPath("$[0].startsAt").value(FUTURO + "T08:00:00"))
            .andExpect(jsonPath("$[0].endsAt").value(FUTURO + "T08:50:00"))
            .andExpect(jsonPath("$[0].status").value("SCHEDULED"));

        // 08:00 em Fortaleza (UTC-3) é 11:00 UTC.
        String utc = jdbc.queryForObject(
            "SELECT to_char(starts_at AT TIME ZONE 'UTC', 'HH24:MI') FROM appointments WHERE id = ?::uuid",
            String.class, id);
        assertThat(utc).isEqualTo("11:00");

        // Dia seguinte não traz a sessão.
        mvc.perform(get("/api/v1/appointments").param("from", "2030-01-08").param("to", "2030-01-08").with(user(OWNER_A)))
            .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void naoDeixaDuasMarcacoesNoMesmoHorarioDaMesmaClinica() throws Exception {
        String primeira = sessao(OWNER_A, mariaA, pilatesA, FUTURO + "T08:00");
        String ana = create("/api/v1/patients", OWNER_A, "{\"cpf\":\"" + cpf() + "\",\"fullName\":\"Ana\"}");

        postJson("/api/v1/appointments", OWNER_A, """
            {"patientId":"%s","serviceId":"%s","startsAt":"%sT08:30"}""".formatted(ana, pilatesA, FUTURO))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value(startsWith("Horário ocupado: conflita com sessão de Maria Souza em 07/01 às 08:00")));

        // Encostado (começa quando a outra termina) pode.
        sessao(OWNER_A, ana, pilatesA, FUTURO + "T08:50");
        // Outra clínica no mesmo horário pode.
        sessao(OWNER_B, joaoB, rpgB, FUTURO + "T08:00");

        // Cancelada libera o horário; reativar a cancelada exige o horário livre de novo.
        mvc.perform(delete("/api/v1/appointments/" + primeira).with(user(OWNER_A)).with(csrf()))
            .andExpect(status().isNoContent());
        String nova = sessao(OWNER_A, ana, pilatesA, FUTURO + "T07:30");
        mudarStatus(OWNER_A, primeira, "SCHEDULED").andExpect(status().isConflict());
        mudarStatus(OWNER_A, nova, "CANCELED").andExpect(status().isOk());
        mudarStatus(OWNER_A, primeira, "SCHEDULED").andExpect(status().isOk());
    }

    @Test
    void bancoTambemBloqueiaSobreposicaoMesmoSemAVerificacaoDaApi() {
        String insert = """
            INSERT INTO appointments(id, clinic_id, kind, starts_at, ends_at)
            VALUES (gen_random_uuid(), ?, 'BLOCK', ?::timestamptz, ?::timestamptz)""";
        jdbc.update(insert, clinicA, "2030-01-07 12:00-03", "2030-01-07 14:00-03");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> jdbc.update(insert, clinicA, "2030-01-07 13:00-03", "2030-01-07 15:00-03"))
            .hasMessageContaining("appointments_no_overlap");
        jdbc.update(insert, clinicB, "2030-01-07 13:00-03", "2030-01-07 15:00-03");
    }

    @Test
    void bloqueioDeHorarioConflitaComSessao() throws Exception {
        String almoco = create("/api/v1/blocks", OWNER_A, """
            {"startsAt":"%sT12:00","endsAt":"%sT14:00","notes":"Almoço"}""".formatted(FUTURO, FUTURO));
        postJson("/api/v1/appointments", OWNER_A, """
            {"patientId":"%s","serviceId":"%s","startsAt":"%sT13:30"}""".formatted(mariaA, pilatesA, FUTURO))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("Horário ocupado: conflita com horário bloqueado em 07/01 às 12:00 (Almoço)"));

        sessao(OWNER_A, mariaA, pilatesA, FUTURO + "T15:00");
        postJson("/api/v1/blocks", OWNER_A, """
            {"startsAt":"%sT14:30","endsAt":"%sT16:00"}""".formatted(FUTURO, FUTURO))
            .andExpect(status().isConflict());

        // Bloqueio não tem status; remover libera o horário.
        mudarStatus(OWNER_A, almoco, "DONE").andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/appointments/" + almoco).with(user(OWNER_A)).with(csrf()))
            .andExpect(status().isNoContent());
        sessao(OWNER_A, mariaA, pilatesA, FUTURO + "T13:00");

        postJson("/api/v1/blocks", OWNER_A, """
            {"startsAt":"%sT18:00","endsAt":"%sT17:00"}""".formatted(FUTURO, FUTURO))
            .andExpect(status().isBadRequest());
    }

    @Test
    void remarcaGuardandoHorarioOriginalEVoltaParaAgendada() throws Exception {
        String id = sessao(OWNER_A, mariaA, pilatesA, FUTURO + "T08:00");
        mudarStatus(OWNER_A, id, "CONFIRMED").andExpect(jsonPath("$.status").value("CONFIRMED"));

        mvc.perform(put("/api/v1/appointments/" + id).with(user(OWNER_A)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"startsAt\":\"2030-01-08T10:00\",\"notes\":\"Pediu para trocar\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.startsAt").value("2030-01-08T10:00:00"))
            .andExpect(jsonPath("$.endsAt").value("2030-01-08T10:50:00"))
            .andExpect(jsonPath("$.status").value("SCHEDULED"))
            .andExpect(jsonPath("$.originalStartsAt").value(FUTURO + "T08:00:00"))
            .andExpect(jsonPath("$.rescheduleCount").value(1))
            .andExpect(jsonPath("$.notes").value("Pediu para trocar"));

        // Segunda remarcação mantém o horário original da primeira.
        mvc.perform(put("/api/v1/appointments/" + id).with(user(OWNER_A)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"startsAt\":\"2030-01-09T10:00\"}"))
            .andExpect(jsonPath("$.originalStartsAt").value(FUTURO + "T08:00:00"))
            .andExpect(jsonPath("$.rescheduleCount").value(2));

        // Remarcar para horário ocupado é recusado.
        String ana = create("/api/v1/patients", OWNER_A, "{\"cpf\":\"" + cpf() + "\",\"fullName\":\"Ana\"}");
        sessao(OWNER_A, ana, pilatesA, "2030-01-10T10:00");
        mvc.perform(put("/api/v1/appointments/" + id).with(user(OWNER_A)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"startsAt\":\"2030-01-10T10:20\"}"))
            .andExpect(status().isConflict());
    }

    @Test
    void realizadaEFaltaSoDepoisDoHorarioESessaoEncerradaNaoSeRemarca() throws Exception {
        String futura = sessao(OWNER_A, mariaA, pilatesA, FUTURO + "T08:00");
        mudarStatus(OWNER_A, futura, "DONE").andExpect(status().isBadRequest());
        mudarStatus(OWNER_A, futura, "NO_SHOW").andExpect(status().isBadRequest());

        String passada = sessao(OWNER_A, mariaA, pilatesA, PASSADO + "T08:00");
        mudarStatus(OWNER_A, passada, "DONE").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"));
        mvc.perform(put("/api/v1/appointments/" + passada).with(user(OWNER_A)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"startsAt\":\"" + FUTURO + "T10:00\"}"))
            .andExpect(status().isBadRequest());
        // Correção de status continua possível.
        mudarStatus(OWNER_A, passada, "NO_SHOW").andExpect(jsonPath("$.status").value("NO_SHOW"));
        mudarStatus(OWNER_A, passada, "INVALIDO").andExpect(status().isBadRequest());
    }

    @Test
    void clinicaNaoVeNemMexeNaAgendaDeOutraClinica() throws Exception {
        String sessaoA = sessao(OWNER_A, mariaA, pilatesA, FUTURO + "T08:00");

        mvc.perform(get("/api/v1/appointments").param("from", FUTURO).param("to", FUTURO).with(user(OWNER_B)))
            .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/v1/appointments/" + sessaoA).with(user(OWNER_B))).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/appointments/" + sessaoA).with(user(OWNER_B)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"startsAt\":\"" + FUTURO + "T10:00\"}"))
            .andExpect(status().isNotFound());
        mudarStatus(OWNER_B, sessaoA, "CANCELED").andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/appointments/" + sessaoA).with(user(OWNER_B)).with(csrf()))
            .andExpect(status().isNotFound());

        // Não agenda com paciente ou serviço da outra clínica.
        postJson("/api/v1/appointments", OWNER_B, """
            {"patientId":"%s","serviceId":"%s","startsAt":"%sT09:00"}""".formatted(mariaA, rpgB, FUTURO))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Paciente não encontrado"));
        postJson("/api/v1/appointments", OWNER_B, """
            {"patientId":"%s","serviceId":"%s","startsAt":"%sT09:00"}""".formatted(joaoB, pilatesA, FUTURO))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Serviço não encontrado"));

        assertThat(jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?::uuid", String.class, sessaoA))
            .isEqualTo("SCHEDULED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM appointments WHERE clinic_id = ?", Integer.class, clinicB))
            .isZero();
    }

    @Test
    void colaboradoraVeAAgendaMasNaoAltera() throws Exception {
        String id = sessao(OWNER_A, mariaA, pilatesA, FUTURO + "T08:00");
        mvc.perform(get("/api/v1/appointments").param("from", FUTURO).param("to", FUTURO).with(user(STAFF_A)))
            .andExpect(jsonPath("$", hasSize(1)));
        postJson("/api/v1/appointments", STAFF_A, """
            {"patientId":"%s","serviceId":"%s","startsAt":"%sT10:00"}""".formatted(mariaA, pilatesA, FUTURO))
            .andExpect(status().isForbidden());
        postJson("/api/v1/blocks", STAFF_A, """
            {"startsAt":"%sT12:00","endsAt":"%sT13:00"}""".formatted(FUTURO, FUTURO))
            .andExpect(status().isForbidden());
        mudarStatus(STAFF_A, id, "CONFIRMED").andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/appointments/" + id).with(user(STAFF_A)).with(csrf()))
            .andExpect(status().isForbidden());
    }

    @Test
    void pacienteOuServicoDesativadoNaoRecebeNovaSessaoMasHistoricoContinua() throws Exception {
        String id = sessao(OWNER_A, mariaA, pilatesA, FUTURO + "T08:00");
        mvc.perform(delete("/api/v1/patients/" + mariaA).with(user(OWNER_A)).with(csrf()));
        postJson("/api/v1/appointments", OWNER_A, """
            {"patientId":"%s","serviceId":"%s","startsAt":"%sT10:00"}""".formatted(mariaA, pilatesA, FUTURO))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/appointments/" + id).with(user(OWNER_A)))
            .andExpect(jsonPath("$.patientName").value("Maria Souza"));
    }

    @Test
    void periodoDaConsultaEhLimitado() throws Exception {
        LocalDate inicio = LocalDate.parse(FUTURO);
        mvc.perform(get("/api/v1/appointments").param("from", FUTURO).param("to", inicio.plusDays(62).toString())
                .with(user(OWNER_A)))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/appointments").param("from", FUTURO).param("to", inicio.minusDays(1).toString())
                .with(user(OWNER_A)))
            .andExpect(status().isBadRequest());
    }
}
