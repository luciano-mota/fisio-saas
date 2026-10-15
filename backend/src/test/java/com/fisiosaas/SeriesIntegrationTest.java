package com.fisiosaas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class SeriesIntegrationTest extends IntegrationTest {
    String mariaA;
    String anaA;
    String pilatesA;

    @BeforeEach
    void cadastros() throws Exception {
        mariaA = create("/api/v1/patients", OWNER_A, "{\"cpf\":\"" + cpf() + "\",\"fullName\":\"Maria Souza\"}");
        anaA = create("/api/v1/patients", OWNER_A, "{\"cpf\":\"" + cpf() + "\",\"fullName\":\"Ana\"}");
        pilatesA = create("/api/v1/services", OWNER_A, "{\"name\":\"Pilates\",\"durationMinutes\":50}");
    }

    private ResultActions serie(String owner, String body) throws Exception {
        return mvc.perform(post("/api/v1/appointments/series").with(user(owner)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String segQuaSex(String patient, String startDate, int weeks, boolean skip) {
        return """
            {"patientId":"%s","serviceId":"%s","startDate":"%s","startTime":"08:00",
             "weekdays":[1,3,5],"weeks":%d,"skipConflicts":%s}""".formatted(patient, pilatesA, startDate, weeks, skip);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @Test
    void segQuaSexAs8hPorCincoSemanasCriaQuinzeSessoesNaSerie() throws Exception {
        serie(OWNER_A, segQuaSex(mariaA, "2030-01-07", 5, false))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.created", hasSize(15)))
            .andExpect(jsonPath("$.skipped", hasSize(0)))
            .andExpect(jsonPath("$.created[0].startsAt").value("2030-01-07T08:00:00"))
            .andExpect(jsonPath("$.created[0].endsAt").value("2030-01-07T08:50:00"))
            .andExpect(jsonPath("$.created[14].startsAt").value("2030-02-08T08:00:00"));

        assertThat(count("SELECT count(DISTINCT series_id) FROM appointments WHERE clinic_id = ?", clinicA)).isEqualTo(1);
        mvc.perform(get("/api/v1/appointments").param("from", "2030-01-14").param("to", "2030-01-20").with(user(OWNER_A)))
            .andExpect(jsonPath("$", hasSize(3)))
            .andExpect(jsonPath("$[1].startsAt").value("2030-01-16T08:00:00"))
            .andExpect(jsonPath("$[1].seriesId").isNotEmpty());
    }

    @Test
    void dataOcupadaRecusaTudoEListaConflitosOuPulaSeOPainelPedir() throws Exception {
        create("/api/v1/appointments", OWNER_A, """
            {"patientId":"%s","serviceId":"%s","startsAt":"2030-01-09T08:30"}""".formatted(anaA, pilatesA));

        serie(OWNER_A, segQuaSex(mariaA, "2030-01-07", 2, false))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("1 de 6 datas estão ocupadas"))
            .andExpect(jsonPath("$.conflicts", hasSize(1)))
            .andExpect(jsonPath("$.conflicts[0]").value("qua, 09/01: sessão de Ana em 09/01 às 08:30"));
        assertThat(count("SELECT count(*) FROM appointments WHERE clinic_id = ?", clinicA)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM appointment_series")).isZero();

        serie(OWNER_A, segQuaSex(mariaA, "2030-01-07", 2, true))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.created", hasSize(5)))
            .andExpect(jsonPath("$.skipped[0]").value("qua, 09/01: sessão de Ana em 09/01 às 08:30"));
    }

    @Test
    void bloqueioRecorrenteDeAlmocoImpedeSessaoNoHorario() throws Exception {
        serie(OWNER_A, """
            {"kind":"BLOCK","startDate":"2030-01-07","startTime":"12:00","durationMinutes":60,
             "weekdays":[1,2,3,4,5],"weeks":1,"notes":"Almoço"}""")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.created", hasSize(5)))
            .andExpect(jsonPath("$.created[0].kind").value("BLOCK"))
            .andExpect(jsonPath("$.created[0].notes").value("Almoço"));

        mvc.perform(post("/api/v1/appointments").with(user(OWNER_A)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"patientId":"%s","serviceId":"%s","startsAt":"2030-01-10T12:30"}""".formatted(mariaA, pilatesA)))
            .andExpect(status().isConflict());
        // Fim de semana ficou livre.
        create("/api/v1/appointments", OWNER_A, """
            {"patientId":"%s","serviceId":"%s","startsAt":"2030-01-12T12:00"}""".formatted(mariaA, pilatesA));

        serie(OWNER_A, """
            {"kind":"BLOCK","patientId":"%s","startDate":"2030-01-07","startTime":"18:00","durationMinutes":60,
             "weekdays":[1],"weeks":1}""".formatted(mariaA))
            .andExpect(status().isBadRequest());
        serie(OWNER_A, """
            {"kind":"BLOCK","startDate":"2030-01-07","startTime":"18:00","weekdays":[1],"weeks":1}""")
            .andExpect(status().isBadRequest());
    }

    @Test
    void cancelaAsProximasDaSerieSemMexerNoQueJaAconteceu() throws Exception {
        String body = mvc.perform(post("/api/v1/appointments/series").with(user(OWNER_A)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(segQuaSex(mariaA, "2020-03-02", 2, false)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var created = json.readTree(body);
        String seriesId = created.get("seriesId").asText();
        String quarta = created.get("created").get(4).get("id").asText(); // qua, 11/03
        mvc.perform(post("/api/v1/appointments/" + quarta + "/status").with(user(OWNER_A)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DONE\"}"))
            .andExpect(status().isOk());

        mvc.perform(delete("/api/v1/appointments/series/" + seriesId).param("from", "2020-03-09")
                .with(user(OWNER_A)).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.canceled").value(2));

        assertThat(count("SELECT count(*) FROM appointments WHERE series_id = ?::uuid AND status = 'SCHEDULED'", seriesId))
            .isEqualTo(3);
        assertThat(count("SELECT count(*) FROM appointments WHERE series_id = ?::uuid AND status = 'DONE'", seriesId))
            .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM appointments WHERE series_id = ?::uuid AND status = 'CANCELED'", seriesId))
            .isEqualTo(2);
    }

    @Test
    void clinicaNaoUsaPacienteNemCancelaSerieDeOutraClinica() throws Exception {
        String body = mvc.perform(post("/api/v1/appointments/series").with(user(OWNER_A)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(segQuaSex(mariaA, "2030-01-07", 1, false)))
            .andReturn().getResponse().getContentAsString();
        String seriesA = json.readTree(body).get("seriesId").asText();

        String rpgB = create("/api/v1/services", OWNER_B, "{\"name\":\"RPG\"}");
        serie(OWNER_B, """
            {"patientId":"%s","serviceId":"%s","startDate":"2030-01-07","startTime":"08:00","weekdays":[1],"weeks":1}"""
            .formatted(mariaA, rpgB))
            .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/appointments/series/" + seriesA).with(user(OWNER_B)).with(csrf()))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/appointments").param("from", "2030-01-07").param("to", "2030-01-13").with(user(OWNER_B)))
            .andExpect(jsonPath("$", hasSize(0)));
        assertThat(count("SELECT count(*) FROM appointments WHERE series_id = ?::uuid AND status = 'SCHEDULED'", seriesA))
            .isEqualTo(3);
    }

    @Test
    void colaboradoraNaoCriaNemCancelaSerie() throws Exception {
        serie(STAFF_A, segQuaSex(mariaA, "2030-01-07", 1, false)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/appointments/series/" + java.util.UUID.randomUUID()).with(user(STAFF_A)).with(csrf()))
            .andExpect(status().isForbidden());
    }

    @Test
    void validaDiasDaSemanaELimite() throws Exception {
        for (String weekdays : new String[] {"[]", "[0]", "[8]"}) {
            serie(OWNER_A, """
                {"patientId":"%s","serviceId":"%s","startDate":"2030-01-07","startTime":"08:00","weekdays":%s,"weeks":1}"""
                .formatted(mariaA, pilatesA, weekdays))
                .andExpect(status().isBadRequest());
        }
        serie(OWNER_A, """
            {"patientId":"%s","serviceId":"%s","startDate":"2030-01-07","startTime":"08:00","weekdays":[1,2,3,4,5,6,7],"weeks":52}"""
            .formatted(mariaA, pilatesA))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Máximo de 100 ocorrências por vez"));
    }
}
