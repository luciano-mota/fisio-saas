package com.fisiosaas;

import static com.fisiosaas.LogoIntegrationTest.image;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.awt.Color;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Equipe multiprofissional: cadastro, foto opcional, vínculo com serviço e seção "Nossa equipe" no site; isolamento. */
class ProfessionalIntegrationTest extends IntegrationTest {

    private ResultActions send(MockHttpServletRequestBuilder req, String asUser, String body) throws Exception {
        return mvc.perform(req.with(user(asUser)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String professional(String owner, String name, String profession) throws Exception {
        return create("/api/v1/professionals", owner, """
            {"fullName":"%s","profession":"%s","council":"CRN-5 12345","bio":"Atende adultos e idosos."}""".formatted(name, profession));
    }

    private ResultActions photo(String owner, String id) throws Exception {
        return mvc.perform(multipart("/api/v1/professionals/" + id + "/photo")
            .file(new MockMultipartFile("file", "foto.jpg", "image/jpeg", image("jpg", 600, 800, Color.PINK)))
            .with(user(owner)).with(csrf()));
    }

    @Test
    void cadastraEditaOrdenaEDesativaSemApagar() throws Exception {
        String paula = professional(OWNER_A, "Paula  Nunes", "Nutricionista");
        String rui = professional(OWNER_A, "Rui Lima", "Psicólogo");
        send(post("/api/v1/professionals"), STAFF_A, "{\"fullName\":\"X\",\"profession\":\"Y\"}").andExpect(status().isForbidden());
        send(post("/api/v1/professionals"), OWNER_A, "{\"fullName\":\" \",\"profession\":\"Y\"}").andExpect(status().isBadRequest());

        mvc.perform(get("/api/v1/professionals").with(user(STAFF_A)))
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[0].fullName").value("Paula Nunes"))
            .andExpect(jsonPath("$[0].council").value("CRN-5 12345"));
        send(post("/api/v1/professionals/" + rui + "/move"), OWNER_A, "{\"direction\":\"up\"}").andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/professionals").with(user(OWNER_A))).andExpect(jsonPath("$[0].fullName").value("Rui Lima"));

        send(put("/api/v1/professionals/" + paula), OWNER_A, "{\"fullName\":\"Paula Nunes\",\"profession\":\"Nutricionista esportiva\"}")
            .andExpect(jsonPath("$.profession").value("Nutricionista esportiva"));
        mvc.perform(delete("/api/v1/professionals/" + paula).with(user(OWNER_A)).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/professionals").with(user(OWNER_A)))
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[?(@.fullName=='Paula Nunes')].active").value(false));
    }

    @Test
    void outraClinicaNaoLeNemAlteraNemVinculaAoServico() throws Exception {
        String paula = professional(OWNER_A, "Paula Nunes", "Nutricionista");
        mvc.perform(get("/api/v1/professionals").with(user(OWNER_B))).andExpect(jsonPath("$", hasSize(0)));
        send(put("/api/v1/professionals/" + paula), OWNER_B, "{\"fullName\":\"Invasão\",\"profession\":\"X\"}").andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/professionals/" + paula).with(user(OWNER_B)).with(csrf())).andExpect(status().isNotFound());
        photo(OWNER_B, paula).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/professionals/" + paula + "/photo").with(user(OWNER_B))).andExpect(status().isNotFound());
        // Vincular o profissional da Ana a um serviço da Bia: 404, sem revelar que existe.
        send(post("/api/v1/services"), OWNER_B, "{\"name\":\"Consulta\",\"professionalId\":\"" + paula + "\"}").andExpect(status().isNotFound());
        String servicoB = create("/api/v1/services", OWNER_B, "{\"name\":\"Consulta\"}");
        send(put("/api/v1/services/" + servicoB), OWNER_B, "{\"name\":\"Consulta\",\"professionalId\":\"" + paula + "\"}").andExpect(status().isNotFound());
    }

    @Test
    void servicoMostraQuemAtendeNoPainelENoSite() throws Exception {
        String paula = professional(OWNER_A, "Paula Nunes", "Nutricionista");
        String servico = create("/api/v1/services", OWNER_A, "{\"name\":\"Consulta nutricional\",\"professionalId\":\"" + paula + "\"}");
        mvc.perform(get("/api/v1/services").with(user(OWNER_A)))
            .andExpect(jsonPath("$[0].professionalName").value("Paula Nunes"))
            .andExpect(jsonPath("$[0].professionalProfession").value("Nutricionista"));
        mvc.perform(get("/api/v1/public/clinics/fisio-ana"))
            .andExpect(jsonPath("$.services[0].professional.fullName").value("Paula Nunes"))
            .andExpect(jsonPath("$.site.team", hasSize(1)))
            .andExpect(jsonPath("$.site.team[0].profession").value("Nutricionista"));
        // Tirar o vínculo.
        send(put("/api/v1/services/" + servico), OWNER_A, "{\"name\":\"Consulta nutricional\"}")
            .andExpect(jsonPath("$.professionalId").doesNotExist());
    }

    @Test
    void fotoOpcionalNoSiteEControlesDaEquipe() throws Exception {
        String paula = professional(OWNER_A, "Paula Nunes", "Nutricionista");
        String rui = professional(OWNER_A, "Rui Lima", "Psicólogo");
        photo(OWNER_A, paula).andExpect(status().isOk()).andExpect(jsonPath("$.photoVersion").isNumber());
        mvc.perform(multipart("/api/v1/professionals/" + rui + "/photo")
                .file(new MockMultipartFile("file", "x.png", "image/png", image("png", 600, 600, Color.RED)))
                .with(user(OWNER_A)).with(csrf()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Envie a foto em JPG"));

        mvc.perform(get("/api/v1/public/clinics/fisio-ana"))
            .andExpect(jsonPath("$.site.team", hasSize(2)))
            .andExpect(jsonPath("$.site.team[0].photoVersion").isNumber())
            .andExpect(jsonPath("$.site.team[1].photoVersion").doesNotExist());
        mvc.perform(get("/api/v1/public/clinics/fisio-ana/professionals/" + paula + "/photo"))
            .andExpect(status().isOk()).andExpect(content().contentType("image/jpeg"));
        // A Bia não serve a foto da equipe da Ana pelo endereço dela.
        mvc.perform(get("/api/v1/public/clinics/fisio-bia/professionals/" + paula + "/photo")).andExpect(status().isNotFound());

        // Clínica desliga as fotos: some a foto do site (a equipe continua).
        send(put("/api/v1/professionals/settings"), OWNER_A, "{\"showTeam\":true,\"showPhotos\":false}").andExpect(status().isOk());
        mvc.perform(get("/api/v1/public/clinics/fisio-ana")).andExpect(jsonPath("$.site.team[0].photoVersion").doesNotExist());
        mvc.perform(get("/api/v1/public/clinics/fisio-ana/professionals/" + paula + "/photo")).andExpect(status().isNotFound());
        // Profissional fora do site e seção desligada.
        send(put("/api/v1/professionals/" + rui), OWNER_A, "{\"fullName\":\"Rui Lima\",\"profession\":\"Psicólogo\",\"showOnSite\":false}");
        mvc.perform(get("/api/v1/public/clinics/fisio-ana")).andExpect(jsonPath("$.site.team", hasSize(1)));
        send(put("/api/v1/professionals/settings"), OWNER_A, "{\"showTeam\":false,\"showPhotos\":true}");
        mvc.perform(get("/api/v1/public/clinics/fisio-ana")).andExpect(jsonPath("$.site.team", hasSize(0)));
        send(put("/api/v1/professionals/settings"), STAFF_A, "{\"showTeam\":true,\"showPhotos\":true}").andExpect(status().isForbidden());

        // Remover a foto apaga de verdade.
        mvc.perform(delete("/api/v1/professionals/" + paula + "/photo").with(user(OWNER_A)).with(csrf()))
            .andExpect(jsonPath("$.photoVersion").doesNotExist());
        mvc.perform(get("/api/v1/professionals/" + paula + "/photo").with(user(OWNER_A))).andExpect(status().isNotFound());
        org.assertj.core.api.Assertions.assertThat(
            jdbc.queryForObject("SELECT count(*) FROM professionals WHERE photo IS NOT NULL", Integer.class)).isZero();
    }
}
