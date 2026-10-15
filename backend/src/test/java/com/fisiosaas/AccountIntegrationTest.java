package com.fisiosaas;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Troca de senha e equipe da clínica (acesso com senha temporária, desativar, responsável sempre presente). */
class AccountIntegrationTest extends IntegrationTest {

    private ResultActions send(MockHttpServletRequestBuilder request, String asUser, String body) throws Exception {
        return mvc.perform(request.with(user(asUser)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").param("email", email).param("password", password).with(csrf()));
    }

    private String id(String email) {
        return jdbc.queryForObject("SELECT id::text FROM users WHERE email = ?", String.class, email);
    }

    @Test
    void trocaDeSenhaConfereASenhaAtualEAForca() throws Exception {
        send(put("/api/v1/account/password"), OWNER_A, "{\"current\":\"errada\",\"password\":\"nova-senha-segura-1\"}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Senha atual incorreta"));
        send(put("/api/v1/account/password"), OWNER_A, "{\"current\":\"" + PASSWORD + "\",\"password\":\"curta\"}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("A senha precisa ter pelo menos 12 caracteres"));
        send(put("/api/v1/account/password"), OWNER_A, "{\"current\":\"" + PASSWORD + "\",\"password\":\"123456789012\"}")
            .andExpect(status().isBadRequest());
        send(put("/api/v1/account/password"), OWNER_A, "{\"current\":\"" + PASSWORD + "\",\"password\":\"nova-senha-segura-1\"}")
            .andExpect(status().isNoContent());
        login(OWNER_A, PASSWORD).andExpect(status().isUnauthorized());
        login(OWNER_A, "nova-senha-segura-1").andExpect(status().isOk());
    }

    @Test
    void responsavelDaAcessoComSenhaTemporaria() throws Exception {
        String body = send(post("/api/v1/team"), OWNER_A, "{\"email\":\"Nova@Fisio-A.test\",\"fullName\":\"Clara\",\"role\":\"STAFF\"}")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.member.email").value("nova@fisio-a.test"))
            .andExpect(jsonPath("$.member.mustChangePassword").value(true))
            .andExpect(jsonPath("$.temporaryPassword", matchesPattern("[A-Za-z0-9]{4}-[A-Za-z0-9]{4}-[A-Za-z0-9]{4}")))
            .andReturn().getResponse().getContentAsString();
        JsonNode access = json.readTree(body);
        String temporary = access.get("temporaryPassword").asText();
        login("nova@fisio-a.test", temporary).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").with(user("nova@fisio-a.test")))
            .andExpect(jsonPath("$.mustChangePassword").value(true)).andExpect(jsonPath("$.role").value("STAFF"));
        send(put("/api/v1/account/password"), "nova@fisio-a.test", "{\"current\":\"" + temporary + "\",\"password\":\"senha-da-clara-1\"}")
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").with(user("nova@fisio-a.test"))).andExpect(jsonPath("$.mustChangePassword").value(false));

        // E-mail já usado (mesmo em outra clínica) não cria outro acesso.
        send(post("/api/v1/team"), OWNER_A, "{\"email\":\"" + OWNER_B + "\",\"role\":\"STAFF\"}")
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("Este e-mail já tem acesso ao sistema. Use outro e-mail."));
        // Colaborador não mexe na equipe.
        send(post("/api/v1/team"), STAFF_A, "{\"email\":\"x@fisio-a.test\",\"role\":\"STAFF\"}").andExpect(status().isForbidden());

        // Desativar: o acesso para de funcionar, mas fica no histórico.
        String clara = access.get("member").get("id").asText();
        mvc.perform(delete("/api/v1/team/" + clara).with(user(OWNER_A)).with(csrf())).andExpect(status().isNoContent());
        login("nova@fisio-a.test", "senha-da-clara-1").andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/team").with(user(OWNER_A))).andExpect(jsonPath("$", hasSize(3)))
            .andExpect(jsonPath("$[2].active").value(false));
        mvc.perform(post("/api/v1/team/" + clara + "/reactivate").with(user(OWNER_A)).with(csrf()))
            .andExpect(jsonPath("$.active").value(true));

        // Redefinir senha gera outra temporária.
        mvc.perform(post("/api/v1/team/" + clara + "/reset-password").with(user(OWNER_A)).with(csrf()))
            .andExpect(jsonPath("$.member.mustChangePassword").value(true)).andExpect(jsonPath("$.temporaryPassword").isNotEmpty());
    }

    @Test
    void clinicaNuncaFicaSemResponsavelEOutraClinicaNaoMexe() throws Exception {
        String owner = id(OWNER_A);
        String staff = id(STAFF_A);
        mvc.perform(delete("/api/v1/team/" + owner).with(user(OWNER_A)).with(csrf()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Você não pode remover o seu próprio acesso"));
        send(put("/api/v1/team/" + owner), OWNER_A, "{\"role\":\"STAFF\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("A clínica precisa ter pelo menos uma responsável ativa"));
        // Promover a colaboradora a responsável libera a troca.
        send(put("/api/v1/team/" + staff), OWNER_A, "{\"fullName\":\"Recepção\",\"role\":\"OWNER\"}")
            .andExpect(jsonPath("$.role").value("OWNER"));
        send(put("/api/v1/team/" + owner), OWNER_A, "{\"role\":\"STAFF\"}").andExpect(jsonPath("$.role").value("STAFF"));

        // Isolamento: a clínica B não vê nem altera a equipe da A.
        mvc.perform(get("/api/v1/team").with(user(OWNER_B))).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(delete("/api/v1/team/" + staff).with(user(OWNER_B)).with(csrf())).andExpect(status().isNotFound());
        send(put("/api/v1/team/" + staff), OWNER_B, "{\"role\":\"STAFF\"}").andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/team/" + staff + "/reset-password").with(user(OWNER_B)).with(csrf())).andExpect(status().isNotFound());
    }
}
