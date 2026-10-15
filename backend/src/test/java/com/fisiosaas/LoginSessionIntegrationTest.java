package com.fisiosaas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

/** Login guardado no banco: continua valendo depois de reiniciar o app e cai quando o acesso é removido. */
class LoginSessionIntegrationTest extends IntegrationTest {

    private Cookie login(String email) throws Exception {
        Cookie session = mvc.perform(post("/api/v1/auth/login").param("email", email).param("password", PASSWORD).with(csrf()))
            .andExpect(status().isOk()).andReturn().getResponse().getCookie("SESSION");
        assertThat(session).as("cookie de sessão").isNotNull();
        assertThat(session.isHttpOnly()).isTrue();
        return session;
    }

    private int sessionsOf(String email) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE lower(principal_name) = ?", Integer.class, email);
        return n == null ? 0 : n;
    }

    @Test
    void sessaoFicaNoBancoESobreviveAoReinicio() throws Exception {
        jdbc.update("DELETE FROM spring_session");
        Cookie session = login(OWNER_A);
        assertThat(sessionsOf(OWNER_A)).isEqualTo(1);
        // Um app recém-iniciado não tem nada em memória: só o cookie + a linha no banco bastam.
        mvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value(OWNER_A));

        mvc.perform(post("/api/v1/auth/logout").cookie(session).with(csrf())).andExpect(status().isNoContent());
        assertThat(sessionsOf(OWNER_A)).isZero();
        mvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void removerAcessoDerrubaOLoginAberto() throws Exception {
        jdbc.update("DELETE FROM spring_session");
        Cookie session = login(STAFF_A);
        String staff = jdbc.queryForObject("SELECT id::text FROM users WHERE email = ?", String.class, STAFF_A);
        mvc.perform(delete("/api/v1/team/" + staff).with(user(OWNER_A)).with(csrf())).andExpect(status().isNoContent());
        assertThat(sessionsOf(STAFF_A)).isZero();
        mvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isUnauthorized());
    }
}
