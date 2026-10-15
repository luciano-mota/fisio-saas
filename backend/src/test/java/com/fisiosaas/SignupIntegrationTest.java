package com.fisiosaas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fisiosaas.identity.SignupController.SignupThrottle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Cadastro de clínica pelo site: aceite dos termos, senha forte, e-mail único, endereço único e clínica isolada. */
class SignupIntegrationTest extends IntegrationTest {
    @Autowired SignupThrottle throttle;

    @BeforeEach
    void resetThrottle() {
        throttle.reset();
    }

    private ResultActions signup(String body) throws Exception {
        return mvc.perform(post("/api/v1/public/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void criaClinicaEResponsavelQueJaEntra() throws Exception {
        signup("""
            {"clinicName":"Fisio Ana","professionalName":"Ana Lima","email":"Nova@Clinica.test","password":"minha-senha-forte",
             "whatsapp":"(98) 99999-0000","acceptTerms":true}""")
            .andExpect(status().isCreated()).andExpect(jsonPath("$.slug").value("fisio-ana-2")); // "fisio-ana" já existe
        mvc.perform(post("/api/v1/auth/login").param("email", "nova@clinica.test").param("password", "minha-senha-forte").with(csrf()))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/clinic").with(user("nova@clinica.test")))
            .andExpect(jsonPath("$.slug").value("fisio-ana-2")).andExpect(jsonPath("$.whatsapp").value("98999990000"))
            .andExpect(jsonPath("$.publicPageEnabled").value(false));
        assertThat(jdbc.queryForObject("SELECT terms_version FROM clinics WHERE slug = 'fisio-ana-2'", String.class))
            .isEqualTo("2026-10-15");
        // A clínica nova começa vazia: não vê nada das outras.
        mvc.perform(get("/api/v1/patients").with(user("nova@clinica.test"))).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/team").with(user("nova@clinica.test"))).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void validaTermosSenhaEmailERobo() throws Exception {
        signup("{\"clinicName\":\"X Fisio\",\"email\":\"a@b.test\",\"password\":\"minha-senha-forte\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Para criar a conta, aceite os termos de uso e a política de privacidade"));
        signup("{\"clinicName\":\"X Fisio\",\"email\":\"a@b.test\",\"password\":\"curta\",\"acceptTerms\":true}")
            .andExpect(status().isBadRequest());
        signup("{\"clinicName\":\"X Fisio\",\"email\":\"" + OWNER_A + "\",\"password\":\"minha-senha-forte\",\"acceptTerms\":true}")
            .andExpect(status().isConflict());
        signup("{\"clinicName\":\"X Fisio\",\"email\":\"r@b.test\",\"password\":\"minha-senha-forte\",\"acceptTerms\":true,\"website\":\"x\"}")
            .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM clinics", Integer.class)).isEqualTo(2);
        // Limite por IP.
        throttle.reset();
        for (int i = 0; i < 3; i++) {
            signup("{\"clinicName\":\"Clínica " + i + "\",\"email\":\"c" + i + "@b.test\",\"password\":\"minha-senha-forte\",\"acceptTerms\":true}")
                .andExpect(status().isCreated());
        }
        signup("{\"clinicName\":\"Mais uma\",\"email\":\"c9@b.test\",\"password\":\"minha-senha-forte\",\"acceptTerms\":true}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Muitas tentativas seguidas. Tente de novo mais tarde."));
        mvc.perform(get("/api/v1/public/platform")).andExpect(jsonPath("$.signupEnabled").value(true));
    }
}
