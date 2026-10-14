package com.fisiosaas;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

class ClinicAdminIntegrationTest extends IntegrationTest {

    @Test
    void loginComSenhaCorretaAbreSessaoEMeMostraAClinica() throws Exception {
        var session = mvc.perform(post("/api/v1/auth/login").with(csrf())
                .param("email", OWNER_A).param("password", PASSWORD))
            .andExpect(status().isOk())
            .andReturn().getRequest().getSession();

        mvc.perform(get("/api/v1/auth/me").session((MockHttpSession) session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.clinicName").value("Fisio Ana"))
            .andExpect(jsonPath("$.role").value("OWNER"));
    }

    @Test
    void loginComSenhaErradaRetorna401EApiExigeLogin() throws Exception {
        mvc.perform(post("/api/v1/auth/login").with(csrf())
                .param("email", OWNER_A).param("password", "errada-errada"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/services")).andExpect(status().isUnauthorized());
    }

    @Test
    void escritaSemCsrfEhBloqueada() throws Exception {
        mvc.perform(post("/api/v1/services").with(user(OWNER_A))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Pilates\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void vitrinePublicaEscondePrecoQuandoAClinicaEscolhe() throws Exception {
        String pilates = createService(OWNER_A, """
            {"name":"Pilates","price":90.00,"showPrice":true,"durationMinutes":50}""");
        String rpg = createService(OWNER_A, """
            {"name":"RPG","price":150.00,"showPrice":false}""");
        String drenagem = createService(OWNER_A, """
            {"name":"Drenagem linfática"}""");
        createPackage(OWNER_A, """
            {"serviceId":"%s","name":"10 sessões","sessionCount":10,"price":800.00,"showPrice":true}"""
            .formatted(pilates));
        createPackage(OWNER_A, """
            {"serviceId":"%s","name":"5 sessões","sessionCount":5,"price":650.00,"showPrice":false}"""
            .formatted(rpg));

        mvc.perform(get("/api/v1/public/clinics/fisio-ana"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.displayName").value("Fisio Ana"))
            .andExpect(jsonPath("$.theme.primary").value("#0F766E"))
            // Ordem: display_order, depois nome → Drenagem, Pilates, RPG.
            .andExpect(jsonPath("$.services", hasSize(3)))
            .andExpect(jsonPath("$.services[0].name").value("Drenagem linfática"))
            .andExpect(jsonPath("$.services[0].price").value(nullValue()))
            .andExpect(jsonPath("$.services[1].name").value("Pilates"))
            .andExpect(jsonPath("$.services[1].price").value(90.0))
            .andExpect(jsonPath("$.services[1].packages[0].price").value(800.0))
            .andExpect(jsonPath("$.services[2].name").value("RPG"))
            .andExpect(jsonPath("$.services[2].price").value(nullValue()))
            .andExpect(jsonPath("$.services[2].packages[0].sessionCount").value(5))
            .andExpect(jsonPath("$.services[2].packages[0].price").value(nullValue()));

        // Desativado some da vitrine.
        mvc.perform(delete("/api/v1/services/" + drenagem).with(user(OWNER_A)).with(csrf()))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/public/clinics/fisio-ana"))
            .andExpect(jsonPath("$.services", hasSize(2)));
    }

    @Test
    void paginaPublicaDesativadaNaoAparece() throws Exception {
        jdbc.update("UPDATE clinics SET public_page_enabled = FALSE WHERE id = ?", clinicB);
        mvc.perform(get("/api/v1/public/clinics/fisio-bia")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/public/clinics/nao-existe")).andExpect(status().isNotFound());
    }

    @Test
    void clinicaNaoVeNemAlteraDadosDeOutraClinica() throws Exception {
        String servicoA = createService(OWNER_A, """
            {"name":"Pilates","price":90.00}""");

        mvc.perform(get("/api/v1/services").with(user(OWNER_B)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(put("/api/v1/services/" + servicoA).with(user(OWNER_B)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Invadido\"}"))
            .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/services/" + servicoA).with(user(OWNER_B)).with(csrf()))
            .andExpect(status().isNotFound());
        // Pacote apontando para serviço de outra clínica é recusado.
        mvc.perform(post("/api/v1/packages").with(user(OWNER_B)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"serviceId\":\"%s\",\"name\":\"x\",\"sessionCount\":5}".formatted(servicoA)))
            .andExpect(status().isBadRequest());

        String nome = jdbc.queryForObject("SELECT name FROM services WHERE id = ?::uuid", String.class, servicoA);
        org.assertj.core.api.Assertions.assertThat(nome).isEqualTo("Pilates");
    }

    @Test
    void funcionarioVeCatalogoMasNaoAltera() throws Exception {
        createService(OWNER_A, "{\"name\":\"Pilates\"}");
        mvc.perform(get("/api/v1/services").with(user(STAFF_A)))
            .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(post("/api/v1/services").with(user(STAFF_A)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"RPG\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void perfilDaClinicaValidaCoresESalvaWhatsappSoComDigitos() throws Exception {
        String body = """
            {"displayName":"Fisio Ana","slug":"fisio-ana","colorPrimary":"#1d4ed8","colorSecondary":"#1E3A8A",
             "colorAccent":"#F97316","whatsapp":"(98) 99999-1234","publicPageEnabled":true}""";
        mvc.perform(put("/api/v1/clinic").with(user(OWNER_A)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.colorPrimary").value("#1D4ED8"))
            .andExpect(jsonPath("$.whatsapp").value("98999991234"));

        mvc.perform(put("/api/v1/clinic").with(user(OWNER_A)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Fisio Ana\",\"colorPrimary\":\"vermelho\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void precoNegativoEhRecusado() throws Exception {
        mvc.perform(post("/api/v1/services").with(user(OWNER_A)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Pilates\",\"price\":-1}"))
            .andExpect(status().isBadRequest());
    }

    private String createService(String owner, String body) throws Exception {
        return create("/api/v1/services", owner, body);
    }

    private String createPackage(String owner, String body) throws Exception {
        return create("/api/v1/packages", owner, body);
    }
}
