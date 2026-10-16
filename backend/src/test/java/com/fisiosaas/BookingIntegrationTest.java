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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Agendamento pelo site: horários livres, pedido com CPF, aceite sem duplicar paciente e isolamento entre clínicas. */
class BookingIntegrationTest extends IntegrationTest {
    static final String CPF_MARIA = "52998224725";
    static final String CPF_JOAO = "16899535009";
    static final String CPF_ANA = "71428793860";

    @Autowired BookingThrottle throttle;

    String pilatesA;
    String rpgB;
    LocalDate day;

    @BeforeEach
    void setUp() throws Exception {
        throttle.reset();
        pilatesA = create("/api/v1/services", OWNER_A, "{\"name\":\"Pilates\",\"durationMinutes\":50,\"price\":90}");
        rpgB = create("/api/v1/services", OWNER_B, "{\"name\":\"RPG\",\"durationMinutes\":50}");
        day = LocalDate.now(ZoneId.of("America/Fortaleza")).plusDays(3);
        enableBooking(OWNER_A).andExpect(status().isOk());
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String asUser, String body) throws Exception {
        if (asUser != null) request = request.with(user(asUser));
        return mvc.perform(request.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions enableBooking(String owner) throws Exception {
        StringBuilder hours = new StringBuilder();
        for (int d = 1; d <= 7; d++) {
            hours.append(d > 1 ? "," : "").append("{\"weekday\":").append(d).append(",\"start\":\"08:00\",\"end\":\"18:00\"}");
        }
        return send(put("/api/v1/clinic/booking"), owner, """
            {"enabled":true,"minNoticeHours":2,"maxDays":30,"holdHours":24,"stepMinutes":30,"hours":[%s]}""".formatted(hours));
    }

    private ResultActions request(String slug, String service, String time, String cpf, String name, String whatsapp)
            throws Exception {
        return send(post("/api/v1/public/clinics/" + slug + "/booking-requests"), null, """
            {"serviceId":"%s","startsAt":"%sT%s","fullName":"%s","cpf":"%s","whatsapp":"%s","consent":true}"""
            .formatted(service, day, time, name, cpf, whatsapp));
    }

    private String pendingId(String owner) throws Exception {
        String body = mvc.perform(get("/api/v1/booking-requests").with(user(owner)))
            .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get(0).get("id").asText();
    }

    @Test
    void horariosLivresSaemDoAtendimentoESomemQuandoPedidos() throws Exception {
        String slots = "/api/v1/public/clinics/fisio-ana/slots?serviceId=" + pilatesA + "&from=" + day + "&to=" + day;
        mvc.perform(get(slots)).andExpect(status().isOk()).andExpect(jsonPath("$['" + day + "']", hasItem("10:00")));

        request("fisio-ana", pilatesA, "10:00", CPF_MARIA, "Maria Souza", "(98) 98888-1111")
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.message").value("Pedido enviado! A clínica vai confirmar o horário pelo WhatsApp."));
        mvc.perform(get(slots)).andExpect(jsonPath("$['" + day + "']", not(hasItem("10:00"))))
            .andExpect(jsonPath("$['" + day + "']", not(hasItem("09:30")))); // 09:30 + 50 min encostaria no pedido
        // Outra pessoa no mesmo horário: recusado.
        request("fisio-ana", pilatesA, "10:00", CPF_JOAO, "João", "98988882222").andExpect(status().isConflict());
        // Fora do horário de atendimento também.
        request("fisio-ana", pilatesA, "19:00", CPF_JOAO, "João", "98988882222").andExpect(status().isConflict());

        // Site mostra que dá para agendar e o horário de atendimento.
        mvc.perform(get("/api/v1/public/clinics/fisio-ana"))
            .andExpect(jsonPath("$.booking.enabled").value(true))
            .andExpect(jsonPath("$.booking.hours", hasSize(7)))
            .andExpect(jsonPath("$.booking.hours[0].start").value("08:00"));
    }

    @Test
    void pedidoValidaCpfConsentimentoERobo() throws Exception {
        request("fisio-ana", pilatesA, "10:00", "12345678900", "Maria", "98988881111")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("CPF inválido. Confira os números."));
        send(post("/api/v1/public/clinics/fisio-ana/booking-requests"), null, """
            {"serviceId":"%s","startsAt":"%sT10:00","fullName":"Maria","cpf":"%s","whatsapp":"98988881111"}"""
            .formatted(pilatesA, day, CPF_MARIA)).andExpect(status().isBadRequest());
        // Campo escondido preenchido: responde igual, mas não grava.
        send(post("/api/v1/public/clinics/fisio-ana/booking-requests"), null, """
            {"serviceId":"%s","startsAt":"%sT10:00","fullName":"Bot","cpf":"%s","whatsapp":"98988881111","consent":true,"website":"spam"}"""
            .formatted(pilatesA, day, CPF_MARIA)).andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_requests", Integer.class)).isZero();
        // Sem CSRF não passa.
        mvc.perform(post("/api/v1/public/clinics/fisio-ana/booking-requests").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void cpfJaCadastradoNaoDuplicaPacienteEPodeAtualizarDados() throws Exception {
        String maria = create("/api/v1/patients", OWNER_A,
            "{\"fullName\":\"Maria Souza\",\"cpf\":\"" + CPF_MARIA + "\",\"whatsapp\":\"98977770000\"}");
        request("fisio-ana", pilatesA, "10:00", CPF_MARIA, "Maria Souza Lima", "(98) 98888-1111").andExpect(status().isAccepted());

        String id = pendingId(OWNER_A);
        mvc.perform(get("/api/v1/booking-requests").with(user(OWNER_A)))
            .andExpect(jsonPath("$[0].patient.id").value(maria))
            .andExpect(jsonPath("$[0].patient.whatsapp").value("98977770000"))
            .andExpect(jsonPath("$[0].whatsapp").value("98988881111"));
        // O contador traz os pedidos mais recentes, para o painel avisar sem recarregar; a outra clínica não vê.
        mvc.perform(get("/api/v1/booking-requests/count").with(user(OWNER_A)))
            .andExpect(jsonPath("$.pending").value(1))
            .andExpect(jsonPath("$.recent[0].id").value(id))
            .andExpect(jsonPath("$.recent[0].fullName").value("Maria Souza Lima"))
            .andExpect(jsonPath("$.recent[0].serviceName").value("Pilates"))
            .andExpect(jsonPath("$.recent[0].startsAt").value(day + "T10:00:00"))
            .andExpect(jsonPath("$.recent[0].createdAt").isNumber());
        mvc.perform(get("/api/v1/booking-requests/count").with(user(OWNER_B)))
            .andExpect(jsonPath("$.pending").value(0)).andExpect(jsonPath("$.recent.length()").value(0));

        send(post("/api/v1/booking-requests/" + id + "/accept"), OWNER_A, "{\"updatePatient\":true}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACCEPTED"))
            .andExpect(jsonPath("$.appointmentId").isNotEmpty());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patients WHERE clinic_id = ?", Integer.class, clinicA)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT whatsapp FROM patients WHERE id = ?::uuid", String.class, maria)).isEqualTo("98988881111");
        mvc.perform(get("/api/v1/appointments?from=" + day + "&to=" + day).with(user(OWNER_A)))
            .andExpect(jsonPath("$[0].patientId").value(maria))
            .andExpect(jsonPath("$[0].startsAt").value(day + "T10:00:00"));
        // Responder de novo não cria outra sessão.
        send(post("/api/v1/booking-requests/" + id + "/accept"), OWNER_A, "{}").andExpect(status().isConflict());
        send(post("/api/v1/booking-requests/" + id + "/decline"), OWNER_A, "{}").andExpect(status().isConflict());
    }

    @Test
    void cpfNovoCriaPacienteUmaVezEPedidoSeguinteReconhece() throws Exception {
        request("fisio-ana", pilatesA, "10:00", CPF_JOAO, "João Pedro", "98988882222").andExpect(status().isAccepted());
        send(post("/api/v1/booking-requests/" + pendingId(OWNER_A) + "/accept"), OWNER_A, "{}").andExpect(status().isOk());
        String joao = jdbc.queryForObject("SELECT id::text FROM patients WHERE cpf = ?", String.class, CPF_JOAO);

        request("fisio-ana", pilatesA, "14:00", CPF_JOAO, "Joao P", "98988882222").andExpect(status().isAccepted());
        mvc.perform(get("/api/v1/booking-requests").with(user(OWNER_A))).andExpect(jsonPath("$[0].patient.id").value(joao));
        send(post("/api/v1/booking-requests/" + pendingId(OWNER_A) + "/accept"), OWNER_A, "{}").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patients WHERE cpf = ?", Integer.class, CPF_JOAO)).isEqualTo(1);
        // Sem "atualizar", o nome do cadastro fica como estava.
        assertThat(jdbc.queryForObject("SELECT full_name FROM patients WHERE cpf = ?", String.class, CPF_JOAO)).isEqualTo("João Pedro");
    }

    @Test
    void pacienteAntigoSemCpfEhSugeridoPeloWhatsappELigado() throws Exception {
        String antiga = legacyPatient(clinicA, "Ana Paula", "98988883333"); // cadastro antigo, sem CPF
        request("fisio-ana", pilatesA, "10:00", CPF_ANA, "Ana Paula Reis", "98988883333").andExpect(status().isAccepted());
        mvc.perform(get("/api/v1/booking-requests").with(user(OWNER_A)))
            .andExpect(jsonPath("$[0].patient").doesNotExist())
            .andExpect(jsonPath("$[0].suggestions[0].id").value(antiga));
        send(post("/api/v1/booking-requests/" + pendingId(OWNER_A) + "/accept"), OWNER_A, "{\"linkPatientId\":\"" + antiga + "\"}")
            .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT cpf FROM patients WHERE id = ?::uuid", String.class, antiga)).isEqualTo(CPF_ANA);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patients", Integer.class)).isEqualTo(1);
    }

    @Test
    void recusaExpiracaoELimites() throws Exception {
        request("fisio-ana", pilatesA, "10:00", CPF_MARIA, "Maria", "98988881111").andExpect(status().isAccepted());
        String id = pendingId(OWNER_A);
        send(post("/api/v1/booking-requests/" + id + "/decline"), OWNER_A, "{\"reason\":\"Sem horário\"}")
            .andExpect(jsonPath("$.status").value("DECLINED")).andExpect(jsonPath("$.declineReason").value("Sem horário"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_requests", Integer.class)).isEqualTo(1); // nada é apagado

        // Pedido vencido libera o horário, mas a clínica ainda pode aceitar se estiver livre.
        request("fisio-ana", pilatesA, "11:00", CPF_MARIA, "Maria", "98988881111").andExpect(status().isAccepted());
        jdbc.update("UPDATE booking_requests SET hold_until = now() - INTERVAL '1 minute' WHERE status = 'PENDING'");
        String expired = jdbc.queryForObject("SELECT id::text FROM booking_requests WHERE status = 'PENDING'", String.class);
        mvc.perform(get("/api/v1/public/clinics/fisio-ana/slots?serviceId=" + pilatesA + "&from=" + day + "&to=" + day))
            .andExpect(jsonPath("$['" + day + "']", hasItem("11:00")));
        mvc.perform(get("/api/v1/booking-requests").with(user(OWNER_A))).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/v1/booking-requests?status=ALL").with(user(OWNER_A))).andExpect(jsonPath("$", hasSize(2)));
        send(post("/api/v1/booking-requests/" + expired + "/accept"), OWNER_A, "{}").andExpect(status().isOk());

        // No máximo 2 pedidos pendentes por CPF.
        request("fisio-ana", pilatesA, "14:00", CPF_JOAO, "João", "98988882222").andExpect(status().isAccepted());
        request("fisio-ana", pilatesA, "15:00", CPF_JOAO, "João", "98988882222").andExpect(status().isAccepted());
        request("fisio-ana", pilatesA, "16:00", CPF_JOAO, "João", "98988882222").andExpect(status().isBadRequest());
        // E no máximo 5 por IP por hora (este é o 6º).
        request("fisio-ana", pilatesA, "17:00", CPF_ANA, "Ana", "98988883333").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Muitos pedidos seguidos. Tente de novo mais tarde ou chame a clínica no WhatsApp."));
    }

    @Test
    void cadaClinicaSoVeERespondeOsPropriosPedidos() throws Exception {
        request("fisio-ana", pilatesA, "10:00", CPF_MARIA, "Maria", "98988881111").andExpect(status().isAccepted());
        String id = pendingId(OWNER_A);

        mvc.perform(get("/api/v1/booking-requests").with(user(OWNER_B))).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/v1/booking-requests/" + id).with(user(OWNER_B))).andExpect(status().isNotFound());
        send(post("/api/v1/booking-requests/" + id + "/accept"), OWNER_B, "{}").andExpect(status().isNotFound());
        send(post("/api/v1/booking-requests/" + id + "/decline"), OWNER_B, "{}").andExpect(status().isNotFound());
        // Serviço de outra clínica não serve para o site da A.
        request("fisio-ana", rpgB, "11:00", CPF_JOAO, "João", "98988882222").andExpect(status().isBadRequest());
        // B sem agendamento ligado: o site dela não tem horários.
        mvc.perform(get("/api/v1/public/clinics/fisio-bia/slots?serviceId=" + rpgB + "&from=" + day + "&to=" + day))
            .andExpect(status().isNotFound());
        // Configuração de A não vaza para B.
        mvc.perform(get("/api/v1/clinic/booking").with(user(OWNER_B)))
            .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.hours", hasSize(0)));
        // Ligar o paciente de outra clínica no aceite não funciona.
        String paulo = legacyPatient(clinicB, "Paulo", "98988881111"); // sem CPF, de outra clínica
        send(post("/api/v1/booking-requests/" + id + "/accept"), OWNER_A, "{\"linkPatientId\":\"" + paulo + "\"}")
            .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT cpf FROM patients WHERE id = ?::uuid", String.class, paulo)).isNull();
    }

    @Test
    void colaboradoraVeMasNaoRespondeNemConfigura() throws Exception {
        request("fisio-ana", pilatesA, "10:00", CPF_MARIA, "Maria", "98988881111").andExpect(status().isAccepted());
        String id = pendingId(STAFF_A);
        send(post("/api/v1/booking-requests/" + id + "/accept"), STAFF_A, "{}").andExpect(status().isForbidden());
        send(post("/api/v1/booking-requests/" + id + "/decline"), STAFF_A, "{}").andExpect(status().isForbidden());
        enableBooking(STAFF_A).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/clinic/booking").with(user(STAFF_A))).andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void configuracaoValidaIntervalos() throws Exception {
        send(put("/api/v1/clinic/booking"), OWNER_A, """
            {"enabled":true,"minNoticeHours":2,"maxDays":30,"holdHours":24,"stepMinutes":30,
             "hours":[{"weekday":1,"start":"08:00","end":"12:00"},{"weekday":1,"start":"11:00","end":"14:00"}]}""")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Na segunda, dois intervalos se sobrepõem"));
        send(put("/api/v1/clinic/booking"), OWNER_A, """
            {"enabled":true,"minNoticeHours":2,"maxDays":30,"holdHours":24,"stepMinutes":30,"hours":[]}""")
            .andExpect(status().isBadRequest());
        send(put("/api/v1/clinic/booking"), OWNER_A, """
            {"enabled":false,"minNoticeHours":2,"maxDays":30,"holdHours":24,"stepMinutes":45,"hours":[]}""")
            .andExpect(status().isBadRequest());
        // Desligado: o site não aceita pedidos.
        send(put("/api/v1/clinic/booking"), OWNER_A, """
            {"enabled":false,"minNoticeHours":2,"maxDays":30,"holdHours":24,"stepMinutes":30,"hours":[]}""")
            .andExpect(status().isOk());
        request("fisio-ana", pilatesA, "10:00", CPF_MARIA, "Maria", "98988881111").andExpect(status().isNotFound());
    }
}
