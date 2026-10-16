package com.fisiosaas;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Avisos da plataforma: só a equipe cria; cada clínica vê os avisos para todas e os dela, no período. */
class NoticeIntegrationTest extends IntegrationTest {
    static final RequestPostProcessor ADMIN = user("equipe@physioevo.test").roles("PLATFORM_ADMIN");

    private LocalDate today() {
        return LocalDate.now(ZoneId.of("America/Fortaleza"));
    }

    private ResultActions post(String url, RequestPostProcessor who, String body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url).with(who).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String notice(String title, String level, String clinic, String extra) throws Exception {
        String body = """
            {"title":"%s","body":"Texto de %s","level":"%s","display":"MODAL"%s%s}""".formatted(title, title, level,
            clinic == null ? "" : ",\"clinicId\":\"" + clinic + "\"", extra);
        JsonNode created = json.readTree(post("/api/v1/admin/notices", ADMIN, body).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString());
        return created.get("id").asText();
    }

    @Test
    void soAEquipeCriaAvisos() throws Exception {
        post("/api/v1/admin/notices", user(OWNER_A), "{\"title\":\"x\",\"body\":\"y\",\"level\":\"INFO\",\"display\":\"BANNER\"}")
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/notices").with(user(OWNER_A))).andExpect(status().isForbidden());
        notice("Manutenção", "INFO", null, "");
        mvc.perform(get("/api/v1/admin/notices").with(ADMIN))
            .andExpect(jsonPath("$[0].status").value("LIVE"))
            .andExpect(jsonPath("$[0].createdBy").value("equipe@physioevo.test"))
            .andExpect(jsonPath("$[0].clinicName").doesNotExist());
    }

    @Test
    void cadaClinicaVeOsAvisosParaTodasEOsDelaUrgentesPrimeiro() throws Exception {
        notice("Para todas", "INFO", null, "");
        notice("Só da Bia", "URGENT", clinicB.toString(), "");
        notice("Só da Ana", "URGENT", clinicA.toString(), "");

        mvc.perform(get("/api/v1/notices").with(user(STAFF_A)))
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[0].title").value("Só da Ana"))
            .andExpect(jsonPath("$[1].title").value("Para todas"))
            .andExpect(jsonPath("$[0].read").value(false));
        mvc.perform(get("/api/v1/notices").with(user(OWNER_B)))
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[0].title").value("Só da Bia"));
    }

    @Test
    void soAparecemDentroDoPeriodo() throws Exception {
        notice("Futuro", "INFO", null, ",\"startsOn\":\"" + today().plusDays(2) + "\"");
        notice("Já saiu", "INFO", null, ",\"startsOn\":\"" + today().minusDays(5) + "\",\"endsOn\":\"" + today().minusDays(1) + "\"");
        notice("Até hoje", "INFO", null, ",\"endsOn\":\"" + today() + "\"");
        mvc.perform(get("/api/v1/notices").with(user(OWNER_A)))
            .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].title").value("Até hoje"));
        mvc.perform(get("/api/v1/admin/notices").with(ADMIN))
            .andExpect(jsonPath("$[?(@.title=='Futuro')].status").value("SCHEDULED"))
            .andExpect(jsonPath("$[?(@.title=='Já saiu')].status").value("ENDED"));
    }

    @Test
    void lidoPorPessoaEContagemDeLeituras() throws Exception {
        String todas = notice("Novidade", "IMPORTANT", null, "");
        String daBia = notice("Só da Bia", "INFO", clinicB.toString(), "");

        post("/api/v1/notices/" + todas + "/read", user(OWNER_A), "").andExpect(status().isNoContent());
        post("/api/v1/notices/" + todas + "/read", user(OWNER_A), "").andExpect(status().isNoContent()); // de novo: sem erro
        mvc.perform(get("/api/v1/notices").with(user(OWNER_A))).andExpect(jsonPath("$[0].read").value(true));
        mvc.perform(get("/api/v1/notices").with(user(STAFF_A))).andExpect(jsonPath("$[0].read").value(false));
        // Aviso de outra clínica: 404, sem revelar que existe.
        post("/api/v1/notices/" + daBia + "/read", user(OWNER_A), "").andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/admin/notices").with(ADMIN))
            .andExpect(jsonPath("$[?(@.title=='Novidade')].readers").value(1))
            .andExpect(jsonPath("$[?(@.title=='Novidade')].clinicsRead").value(1));
    }

    @Test
    void desativarTiraDaTelaMasGuardaNoHistorico() throws Exception {
        String id = notice("Promoção", "INFO", null, "");
        mvc.perform(delete("/api/v1/admin/notices/" + id).with(ADMIN).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/notices").with(user(OWNER_A))).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/v1/admin/notices").with(ADMIN))
            .andExpect(jsonPath("$[0].status").value("INACTIVE")).andExpect(jsonPath("$[0].active").value(false));
        // Editar e reativar.
        mvc.perform(put("/api/v1/admin/notices/" + id).with(ADMIN).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Promoção de outubro\",\"body\":\"20% off\",\"level\":\"INFO\",\"display\":\"BANNER\",\"active\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("LIVE")).andExpect(jsonPath("$.display").value("BANNER"));
        mvc.perform(get("/api/v1/notices").with(user(OWNER_A))).andExpect(jsonPath("$[0].title").value("Promoção de outubro"));
    }

    @Test
    void clinicaSuspensaVeEMarcaComoLido() throws Exception {
        String id = notice("Regularize", "URGENT", clinicA.toString(), "");
        jdbc.update("UPDATE clinics SET status = 'SUSPENDED' WHERE id = ?", clinicA);
        mvc.perform(get("/api/v1/notices").with(user(OWNER_A))).andExpect(jsonPath("$[0].title").value("Regularize"));
        post("/api/v1/notices/" + id + "/read", user(OWNER_A), "").andExpect(status().isNoContent());
    }

    @Test
    void validaOsCampos() throws Exception {
        post("/api/v1/admin/notices", ADMIN, "{\"title\":\" \",\"body\":\"y\",\"level\":\"INFO\",\"display\":\"BANNER\"}")
            .andExpect(status().isBadRequest());
        post("/api/v1/admin/notices", ADMIN, "{\"title\":\"x\",\"body\":\"y\",\"level\":\"GRITO\",\"display\":\"BANNER\"}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Nível inválido"));
        post("/api/v1/admin/notices", ADMIN, """
            {"title":"x","body":"y","level":"INFO","display":"BANNER","startsOn":"%s","endsOn":"%s"}"""
            .formatted(today(), today().minusDays(1)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("A data de saída deve ser depois do início"));
        post("/api/v1/admin/notices", ADMIN, """
            {"title":"x","body":"y","level":"INFO","display":"BANNER","clinicId":"%s"}""".formatted(java.util.UUID.randomUUID()))
            .andExpect(status().isNotFound());
    }
}
