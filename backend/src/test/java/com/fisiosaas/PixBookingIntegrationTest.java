package com.fisiosaas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fisiosaas.booking.BookingThrottle;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Pix no agendamento: chave da clínica, código com valor, comprovante com token, conferência e isolamento. */
class PixBookingIntegrationTest extends IntegrationTest {
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13};
    static final byte[] PDF = "%PDF-1.4\n%comprovante".getBytes();

    @Autowired BookingThrottle throttle;

    String pilates;
    String hidden;
    LocalDate day;

    @BeforeEach
    void setUp() throws Exception {
        throttle.reset();
        pilates = create("/api/v1/services", OWNER_A, "{\"name\":\"Pilates\",\"durationMinutes\":50,\"price\":90,\"showPrice\":true}");
        hidden = create("/api/v1/services", OWNER_A, "{\"name\":\"RPG\",\"durationMinutes\":50,\"price\":120,\"showPrice\":false}");
        day = LocalDate.now(ZoneId.of("America/Fortaleza")).plusDays(3);
        StringBuilder hours = new StringBuilder();
        for (int d = 1; d <= 7; d++) hours.append(d > 1 ? "," : "").append("{\"weekday\":%d,\"start\":\"08:00\",\"end\":\"18:00\"}".formatted(d));
        send(put("/api/v1/clinic/booking"), OWNER_A, """
            {"enabled":true,"minNoticeHours":2,"maxDays":30,"holdHours":24,"stepMinutes":30,"hours":[%s]}""".formatted(hours))
            .andExpect(status().isOk());
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String asUser, String body) throws Exception {
        if (asUser != null) request = request.with(user(asUser));
        return mvc.perform(request.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void setPix() throws Exception {
        send(put("/api/v1/clinic/pix"), OWNER_A, """
            {"keyType":"TELEFONE","key":"(98) 99999-0000","name":"Ana Fisioterapeuta","city":"São Luís"}""")
            .andExpect(status().isOk()).andExpect(jsonPath("$.key").value("+5598999990000"))
            .andExpect(jsonPath("$.city").value("SAO LUIS"));
    }

    private JsonNode requestPix(String service, String time) throws Exception {
        String body = send(post("/api/v1/public/clinics/fisio-ana/booking-requests"), null, """
            {"serviceId":"%s","startsAt":"%sT%s","fullName":"Maria Souza","cpf":"52998224725","whatsapp":"98988881111",
             "consent":true,"payMethod":"PIX"}""".formatted(service, day, time))
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private ResultActions upload(String slug, String id, String token, byte[] data) throws Exception {
        return mvc.perform(multipart("/api/v1/public/clinics/" + slug + "/booking-requests/" + id + "/receipt")
            .file(new MockMultipartFile("file", "comprovante", "application/octet-stream", data))
            .param("token", token).with(csrf()));
    }

    @Test
    void chavePixValidadaENoSite() throws Exception {
        send(put("/api/v1/clinic/pix"), OWNER_A, "{\"keyType\":\"CPF\",\"key\":\"111.111.111-11\",\"name\":\"Ana\",\"city\":\"Recife\"}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Chave Pix: CPF inválido"));
        send(put("/api/v1/clinic/pix"), STAFF_A, "{\"keyType\":\"EMAIL\",\"key\":\"a@b.com\",\"name\":\"Ana\",\"city\":\"Recife\"}")
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/public/clinics/fisio-ana")).andExpect(jsonPath("$.booking.pix").value(false));
        setPix();
        mvc.perform(get("/api/v1/public/clinics/fisio-ana")).andExpect(jsonPath("$.booking.pix").value(true));
        // A chave de uma clínica não aparece na outra.
        mvc.perform(get("/api/v1/clinic/pix").with(user(OWNER_B))).andExpect(jsonPath("$.key").doesNotExist());

        // Cobrança avulsa (quem está devendo).
        send(post("/api/v1/finance/pix"), OWNER_A, "{\"amount\":150}")
            .andExpect(jsonPath("$.code", startsWith("000201"))).andExpect(jsonPath("$.qrSvg", startsWith("<svg")));
        send(post("/api/v1/finance/pix"), OWNER_B, "{\"amount\":150}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Cadastre a chave Pix em Clínica → Pix"));
    }

    @Test
    void pedidoComPixGeraCodigoEComprovanteEConfirmaPagamento() throws Exception {
        // Sem chave cadastrada, Pix não é oferecido.
        send(post("/api/v1/public/clinics/fisio-ana/booking-requests"), null, """
            {"serviceId":"%s","startsAt":"%sT10:00","fullName":"Maria","cpf":"52998224725","whatsapp":"98988881111",
             "consent":true,"payMethod":"PIX"}""".formatted(pilates, day))
            .andExpect(status().isBadRequest());
        setPix();
        // Preço oculto: também não.
        send(post("/api/v1/public/clinics/fisio-ana/booking-requests"), null, """
            {"serviceId":"%s","startsAt":"%sT10:00","fullName":"Maria","cpf":"52998224725","whatsapp":"98988881111",
             "consent":true,"payMethod":"PIX"}""".formatted(hidden, day))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Pagamento por Pix não disponível para este atendimento"));

        JsonNode sent = requestPix(pilates, "10:00");
        String id = sent.get("requestId").asText();
        String code = sent.get("code").asText();
        assertThat(sent.get("amount").decimalValue()).isEqualByComparingTo("90");
        assertThat(sent.get("pixCode").asText()).startsWith("000201").contains("540590.00").contains(code);
        assertThat(sent.get("qrSvg").asText()).startsWith("<svg");
        String token = sent.get("receiptToken").asText();

        // Token errado ou clínica errada: 404 (não revela o pedido).
        upload("fisio-ana", id, "token-errado", PNG).andExpect(status().isNotFound());
        upload("fisio-bia", id, token, PNG).andExpect(status().isNotFound());
        // Tipo de arquivo conferido pelo conteúdo.
        upload("fisio-ana", id, token, "<html>".getBytes()).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Envie o comprovante como foto (JPG ou PNG) ou PDF"));
        upload("fisio-ana", id, token, PDF).andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("Comprovante enviado! A clínica vai conferir e confirmar pelo WhatsApp."));

        // Painel: vê pagamento escolhido e comprovante; só a responsável abre o arquivo.
        mvc.perform(get("/api/v1/booking-requests/" + id).with(user(OWNER_A)))
            .andExpect(jsonPath("$.payMethod").value("PIX")).andExpect(jsonPath("$.payAmount").value(90.0))
            .andExpect(jsonPath("$.code").value(code)).andExpect(jsonPath("$.receipt.contentType").value("application/pdf"));
        mvc.perform(get("/api/v1/booking-requests/" + id + "/receipt").with(user(OWNER_A)))
            .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"))
            .andExpect(header().string("Content-Security-Policy", "sandbox"));
        mvc.perform(get("/api/v1/booking-requests/" + id + "/receipt").with(user(STAFF_A))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/booking-requests/" + id + "/receipt").with(user(OWNER_B))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/booking-requests/" + id).with(user(OWNER_B))).andExpect(status().isNotFound());

        // Aceitar com o Pix conferido lança o pagamento na sessão.
        String accepted = send(post("/api/v1/booking-requests/" + id + "/accept"), OWNER_A, "{\"confirmPayment\":true}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.paymentId").isNotEmpty())
            .andReturn().getResponse().getContentAsString();
        String patient = jdbc.queryForObject("SELECT patient_id::text FROM booking_requests WHERE id = ?::uuid", String.class, id);
        mvc.perform(get("/api/v1/patients/" + patient + "/finance").with(user(OWNER_A)))
            .andExpect(jsonPath("$.paid").value(90.0))
            .andExpect(jsonPath("$.payments[0].method").value("PIX"))
            .andExpect(jsonPath("$.payments[0].appointmentId").value(json.readTree(accepted).get("appointmentId").asText()));
        // Depois de respondido, não aceita mais comprovante.
        upload("fisio-ana", id, token, PNG).andExpect(status().isBadRequest());
    }

    @Test
    void pagarNaClinicaNaoGeraPixEAceiteSemConferirNaoLancaPagamento() throws Exception {
        setPix();
        String body = send(post("/api/v1/public/clinics/fisio-ana/booking-requests"), null, """
            {"serviceId":"%s","startsAt":"%sT11:00","fullName":"João","cpf":"16899535009","whatsapp":"98988882222",
             "consent":true,"payMethod":"CLINICA"}""".formatted(pilates, day))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.pixCode").doesNotExist())
            .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).get("requestId").isNull()).isTrue();
        String id = jdbc.queryForObject("SELECT id::text FROM booking_requests", String.class);
        mvc.perform(get("/api/v1/booking-requests/" + id).with(user(OWNER_A))).andExpect(jsonPath("$.payMethod").value("CLINICA"));
        send(post("/api/v1/booking-requests/" + id + "/accept"), OWNER_A, "{\"confirmPayment\":true}")
            .andExpect(jsonPath("$.paymentId").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments", Integer.class)).isZero();
    }
}
