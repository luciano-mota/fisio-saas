package com.fisiosaas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fisiosaas.booking.BookingThrottle;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Atendimentos no mesmo horário: limite por clínica na agenda e no site, bloqueio sempre fecha, isolamento. */
class CapacityIntegrationTest extends IntegrationTest {
    static final String FUTURO = "2030-01-07"; // segunda-feira

    @Autowired BookingThrottle throttle;

    String maria;
    String ana;
    String joao;
    String pilates;
    String pacienteB;
    String rpgB;

    @BeforeEach
    void cadastros() throws Exception {
        throttle.reset();
        maria = create("/api/v1/patients", OWNER_A, "{\"cpf\":\"" + cpf() + "\",\"fullName\":\"Maria Souza\"}");
        ana = create("/api/v1/patients", OWNER_A, "{\"cpf\":\"" + cpf() + "\",\"fullName\":\"Ana\"}");
        joao = create("/api/v1/patients", OWNER_A, "{\"cpf\":\"" + cpf() + "\",\"fullName\":\"João\"}");
        pilates = create("/api/v1/services", OWNER_A, "{\"name\":\"Pilates\",\"durationMinutes\":50}");
        pacienteB = create("/api/v1/patients", OWNER_B, "{\"cpf\":\"" + cpf() + "\",\"fullName\":\"Bruna\"}");
        rpgB = create("/api/v1/services", OWNER_B, "{\"name\":\"RPG\",\"durationMinutes\":50}");
    }

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                               String asUser, String body) throws Exception {
        if (asUser != null) request = request.with(user(asUser));
        return mvc.perform(request.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions configurar(String owner, boolean enabled, Integer vagas) throws Exception {
        StringBuilder hours = new StringBuilder();
        for (int d = 1; d <= 7; d++) {
            hours.append(d > 1 ? "," : "").append("{\"weekday\":").append(d).append(",\"start\":\"08:00\",\"end\":\"18:00\"}");
        }
        return send(put("/api/v1/clinic/booking"), owner, """
            {"enabled":%s,"minNoticeHours":2,"maxDays":30,"holdHours":24,"stepMinutes":30,"hours":[%s]%s}"""
            .formatted(enabled, hours, vagas == null ? "" : ",\"maxSimultaneous\":" + vagas));
    }

    private ResultActions sessao(String owner, String patient, String service, String startsAt) throws Exception {
        return send(post("/api/v1/appointments"), owner, """
            {"patientId":"%s","serviceId":"%s","startsAt":"%s"}""".formatted(patient, service, startsAt));
    }

    private String id(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
            .get("id").asText();
    }

    @Test
    void padraoEhUmAtendimentoPorVezESalvarSemOCampoMantem() throws Exception {
        mvc.perform(get("/api/v1/clinic/booking").with(user(OWNER_A))).andExpect(jsonPath("$.maxSimultaneous").value(1));
        configurar(OWNER_A, false, 3).andExpect(status().isOk()).andExpect(jsonPath("$.maxSimultaneous").value(3));
        configurar(OWNER_A, false, null).andExpect(status().isOk()).andExpect(jsonPath("$.maxSimultaneous").value(3));
        configurar(OWNER_A, false, 0).andExpect(status().isBadRequest());
        configurar(OWNER_A, false, 21).andExpect(status().isBadRequest());

        // Com 1 (padrão), continua um por horário.
        configurar(OWNER_A, false, 1).andExpect(status().isOk());
        id(sessao(OWNER_A, maria, pilates, FUTURO + "T08:00"));
        sessao(OWNER_A, ana, pilates, FUTURO + "T08:00").andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("Horário ocupado: conflita com sessão de Maria Souza em 07/01 às 08:00"));
    }

    @Test
    void agendaAceitaAteOLimiteEBloqueioSempreFecha() throws Exception {
        configurar(OWNER_A, false, 2).andExpect(status().isOk());
        id(sessao(OWNER_A, maria, pilates, FUTURO + "T08:00"));
        String daAna = id(sessao(OWNER_A, ana, pilates, FUTURO + "T08:00"));
        sessao(OWNER_A, joao, pilates, FUTURO + "T08:30").andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("Horário lotado: já tem 2 atendimentos ao mesmo tempo (Maria Souza, Ana)"));
        // Encostado (começa quando as outras terminam) cabe.
        id(sessao(OWNER_A, joao, pilates, FUTURO + "T08:50"));
        // A mesma pessoa não fica em duas sessões ao mesmo tempo, mesmo com vaga.
        sessao(OWNER_A, maria, pilates, FUTURO + "T08:30").andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("Horário ocupado: conflita com sessão de Maria Souza em 07/01 às 08:00"));

        // Bloqueio não convive com sessão; sessão não entra em horário bloqueado, mesmo com vaga.
        send(post("/api/v1/blocks"), OWNER_A, """
            {"startsAt":"%sT08:00","endsAt":"%sT09:00"}""".formatted(FUTURO, FUTURO)).andExpect(status().isConflict());
        send(post("/api/v1/blocks"), OWNER_A, """
            {"startsAt":"%sT12:00","endsAt":"%sT14:00","notes":"Almoço"}""".formatted(FUTURO, FUTURO))
            .andExpect(status().isCreated());
        sessao(OWNER_A, ana, pilates, FUTURO + "T12:00").andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("Horário ocupado: conflita com horário bloqueado em 07/01 às 12:00 (Almoço)"));

        // Remarcar e reativar respeitam o limite.
        String deJoao = id(sessao(OWNER_A, joao, pilates, FUTURO + "T15:00"));
        send(put("/api/v1/appointments/" + deJoao), OWNER_A, "{\"startsAt\":\"" + FUTURO + "T08:00\"}")
            .andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/appointments/" + daAna).with(user(OWNER_A)).with(csrf())).andExpect(status().isNoContent());
        send(put("/api/v1/appointments/" + deJoao), OWNER_A, "{\"startsAt\":\"" + FUTURO + "T08:00\"}")
            .andExpect(status().isOk());
        send(post("/api/v1/appointments/" + daAna + "/status"), OWNER_A, "{\"status\":\"SCHEDULED\"}")
            .andExpect(status().isConflict());

        mvc.perform(get("/api/v1/appointments").param("from", FUTURO).param("to", FUTURO).with(user(OWNER_A)))
            .andExpect(jsonPath("$[?(@.startsAt == '" + FUTURO + "T08:00:00')]", hasSize(2)));
    }

    @Test
    void serieCriaSoAsDatasComVaga() throws Exception {
        configurar(OWNER_A, false, 2).andExpect(status().isOk());
        id(sessao(OWNER_A, maria, pilates, "2030-01-14T08:00"));
        id(sessao(OWNER_A, ana, pilates, "2030-01-14T08:00"));
        id(sessao(OWNER_A, ana, pilates, "2030-01-21T08:00"));
        String body = """
            {"kind":"SESSION","patientId":"%s","serviceId":"%s","weekdays":[1],"startDate":"%s","startTime":"08:00",
             "occurrences":3%s}""";
        send(post("/api/v1/appointments/series"), OWNER_A, body.formatted(joao, pilates, FUTURO, ""))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("1 de 3 datas estão ocupadas"))
            .andExpect(jsonPath("$.conflicts[0]").value(org.hamcrest.Matchers.containsString("já tem 2 atendimentos ao mesmo tempo")));
        send(post("/api/v1/appointments/series"), OWNER_A, body.formatted(joao, pilates, FUTURO, ",\"skipConflicts\":true"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.created", hasSize(2)));
    }

    @Test
    void siteOfereceOHorarioAteLotarETodosPassamPelaClinica() throws Exception {
        configurar(OWNER_A, true, 2).andExpect(status().isOk());
        LocalDate day = LocalDate.now(ZoneId.of("America/Fortaleza")).plusDays(3);
        String slots = "/api/v1/public/clinics/fisio-ana/slots?serviceId=" + pilates + "&from=" + day + "&to=" + day;
        String pedido = """
            {"serviceId":"%s","startsAt":"%sT10:00","fullName":"%s","cpf":"%s","whatsapp":"98988881111","consent":true}""";
        String cpf1 = cpf();
        String cpf2 = cpf();

        send(post("/api/v1/public/clinics/fisio-ana/booking-requests"), null, pedido.formatted(pilates, day, "Carla", cpf1))
            .andExpect(status().isAccepted());
        mvc.perform(get(slots)).andExpect(jsonPath("$['" + day + "']", hasItem("10:00")));
        // A mesma pessoa não segura as duas vagas.
        send(post("/api/v1/public/clinics/fisio-ana/booking-requests"), null, pedido.formatted(pilates, day, "Carla", cpf1))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Você já pediu esse horário. Aguarde a clínica confirmar."));
        send(post("/api/v1/public/clinics/fisio-ana/booking-requests"), null, pedido.formatted(pilates, day, "Davi", cpf2))
            .andExpect(status().isAccepted());
        mvc.perform(get(slots)).andExpect(jsonPath("$['" + day + "']", not(hasItem("10:00"))));
        send(post("/api/v1/public/clinics/fisio-ana/booking-requests"), null, pedido.formatted(pilates, day, "Eva", cpf()))
            .andExpect(status().isConflict());

        // Os dois continuam precisando do aceite da clínica, e os dois cabem na agenda.
        String lista = mvc.perform(get("/api/v1/booking-requests").with(user(OWNER_A)))
            .andExpect(jsonPath("$", hasSize(2))).andReturn().getResponse().getContentAsString();
        for (var item : json.readTree(lista)) {
            send(post("/api/v1/booking-requests/" + item.get("id").asText() + "/accept"), OWNER_A, "{}")
                .andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/appointments").param("from", day.toString()).param("to", day.toString()).with(user(OWNER_A)))
            .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void cadaClinicaTemSeuLimiteESessoesDeOutraNaoOcupamVaga() throws Exception {
        configurar(OWNER_A, false, 2).andExpect(status().isOk());
        // B continua com 1 e não vê nem altera o limite de A (o limite vem da clínica logada).
        mvc.perform(get("/api/v1/clinic/booking").with(user(OWNER_B))).andExpect(jsonPath("$.maxSimultaneous").value(1));
        id(sessao(OWNER_B, pacienteB, rpgB, FUTURO + "T08:00"));
        sessao(OWNER_B, create("/api/v1/patients", OWNER_B, "{\"cpf\":\"" + cpf() + "\",\"fullName\":\"Beto\"}"), rpgB,
            FUTURO + "T08:00").andExpect(status().isConflict());
        configurar(OWNER_B, false, 5).andExpect(status().isOk());
        mvc.perform(get("/api/v1/clinic/booking").with(user(OWNER_A))).andExpect(jsonPath("$.maxSimultaneous").value(2));

        // Sessões da clínica B no mesmo horário não ocupam as vagas de A.
        id(sessao(OWNER_A, maria, pilates, FUTURO + "T08:00"));
        id(sessao(OWNER_A, ana, pilates, FUTURO + "T08:00"));
        // Não dá para usar paciente ou serviço de outra clínica para ocupar vaga.
        sessao(OWNER_A, pacienteB, pilates, FUTURO + "T10:00").andExpect(status().isBadRequest());
        // Colaboradora só consulta.
        configurar(STAFF_A, false, 4).andExpect(status().isForbidden());
    }

    @Test
    void doisCliquesAoMesmoTempoNaoPassamDoLimite() throws Exception {
        configurar(OWNER_A, false, 2).andExpect(status().isOk());
        id(sessao(OWNER_A, maria, pilates, FUTURO + "T08:00"));
        // Última vaga das 8h disputada por duas pessoas ao mesmo tempo: só uma entra.
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> results = new ArrayList<>();
        for (String patient : List.of(ana, joao)) {
            Callable<Integer> click = () -> {
                go.await();
                return sessao(OWNER_A, patient, pilates, FUTURO + "T08:00").andReturn().getResponse().getStatus();
            };
            results.add(pool.submit(click));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) statuses.add(f.get());
        pool.shutdown();
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        Integer total = jdbc.queryForObject(
            "SELECT count(*) FROM appointments WHERE clinic_id = ? AND status <> 'CANCELED'", Integer.class, clinicA);
        assertThat(total).isEqualTo(2);
    }
}
