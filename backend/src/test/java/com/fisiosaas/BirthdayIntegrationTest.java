package com.fisiosaas;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fisiosaas.patient.BirthdayController;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class BirthdayIntegrationTest extends IntegrationTest {

    private String patient(String asUser, String name, String birthDate) throws Exception {
        return create("/api/v1/patients", asUser, """
            {"fullName":"%s","cpf":"%s","whatsapp":"98999990000","birthDate":"%s"}""".formatted(name, cpf(), birthDate));
    }

    @Test
    void listaAniversariantesDoDiaComIdadeIncluindoDesativados() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("America/Fortaleza"));
        patient(OWNER_A, "Carla Hoje", today.minusYears(40).toString());
        String inativo = patient(OWNER_A, "Bruno Sumido", today.minusYears(25).toString());
        mvc.perform(delete("/api/v1/patients/" + inativo).with(user(OWNER_A)).with(csrf())).andExpect(status().isNoContent());
        patient(OWNER_A, "Amanhã Silva", today.minusYears(30).plusDays(1).toString());
        create("/api/v1/patients", OWNER_A, "{\"fullName\":\"Sem Data\",\"cpf\":\"" + cpf() + "\"}");

        // Colaboradora também vê (só consulta); ordem alfabética no mesmo dia.
        mvc.perform(get("/api/v1/patients/birthdays").with(user(STAFF_A)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.today").value(today.toString()))
            .andExpect(jsonPath("$.items", hasSize(2)))
            .andExpect(jsonPath("$.items[0].fullName").value("Bruno Sumido"))
            .andExpect(jsonPath("$.items[0].active").value(false))
            .andExpect(jsonPath("$.items[0].age").value(25))
            .andExpect(jsonPath("$.items[1].fullName").value("Carla Hoje"))
            .andExpect(jsonPath("$.items[1].age").value(40))
            .andExpect(jsonPath("$.items[1].whatsapp").value("98999990000"));
    }

    @Test
    void periodoQueAtravessaAViradaDoAno() throws Exception {
        patient(OWNER_A, "Dezembro", "1990-12-31");
        patient(OWNER_A, "Janeiro", "1985-01-02");
        patient(OWNER_A, "Fevereiro", "1985-02-10");
        mvc.perform(get("/api/v1/patients/birthdays").param("from", "2026-12-28").param("to", "2027-01-04").with(user(OWNER_A)))
            .andExpect(jsonPath("$.items", hasSize(2)))
            .andExpect(jsonPath("$.items[0].fullName").value("Dezembro"))
            .andExpect(jsonPath("$.items[0].date").value("2026-12-31"))
            .andExpect(jsonPath("$.items[0].age").value(36))
            .andExpect(jsonPath("$.items[1].fullName").value("Janeiro"))
            .andExpect(jsonPath("$.items[1].date").value("2027-01-02"))
            .andExpect(jsonPath("$.items[1].age").value(42));
    }

    @Test
    void quemNasceuEm29DeFevereiroComemoraDia28NoAnoQueNaoEhBissexto() throws Exception {
        patient(OWNER_A, "Bissexta", "2000-02-29");
        mvc.perform(get("/api/v1/patients/birthdays").param("from", "2027-02-28").with(user(OWNER_A)))
            .andExpect(jsonPath("$.items", hasSize(1)))
            .andExpect(jsonPath("$.items[0].date").value("2027-02-28"))
            .andExpect(jsonPath("$.items[0].age").value(27));
        mvc.perform(get("/api/v1/patients/birthdays").param("from", "2028-02-28").with(user(OWNER_A)))
            .andExpect(jsonPath("$.items", hasSize(0)));
        mvc.perform(get("/api/v1/patients/birthdays").param("from", "2028-02-29").with(user(OWNER_A)))
            .andExpect(jsonPath("$.items[0].date").value("2028-02-29"));
    }

    @Test
    void periodoInvalidoResponde400() throws Exception {
        mvc.perform(get("/api/v1/patients/birthdays").param("from", "2026-10-10").param("to", "2026-10-01").with(user(OWNER_A)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("A data final deve ser depois da inicial"));
        mvc.perform(get("/api/v1/patients/birthdays").param("from", "2026-01-01").param("to", "2027-06-01").with(user(OWNER_A)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void naoMostraAniversariantesDeOutraClinica() throws Exception {
        patient(OWNER_A, "Paciente da Ana", "1980-03-15");
        patient(OWNER_B, "Paciente da Bia", "1980-03-15");
        mvc.perform(get("/api/v1/patients/birthdays").param("from", "2027-03-15").with(user(OWNER_B)))
            .andExpect(jsonPath("$.items", hasSize(1)))
            .andExpect(jsonPath("$.items[0].fullName").value("Paciente da Bia"));
    }

    @Test
    void modeloDeMensagemEhDaClinicaESoAResponsavelAltera() throws Exception {
        mvc.perform(get("/api/v1/patients/birthdays").with(user(OWNER_A)))
            .andExpect(jsonPath("$.template").value(BirthdayController.DEFAULT_MESSAGE))
            .andExpect(jsonPath("$.customTemplate").value(false));
        String body = "{\"message\":\"  Parabéns, {nome}! Ganhou uma sessão de presente 🎁  \"}";
        mvc.perform(put("/api/v1/patients/birthday-message").with(user(STAFF_A)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/patients/birthday-message").with(user(OWNER_A)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.template").value("Parabéns, {nome}! Ganhou uma sessão de presente 🎁"))
            .andExpect(jsonPath("$.customTemplate").value(true));
        // A outra clínica continua com o modelo padrão.
        mvc.perform(get("/api/v1/patients/birthdays").with(user(OWNER_B)))
            .andExpect(jsonPath("$.template").value(BirthdayController.DEFAULT_MESSAGE));
        // Vazio volta ao padrão.
        mvc.perform(put("/api/v1/patients/birthday-message").with(user(OWNER_A)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\" \"}"))
            .andExpect(jsonPath("$.customTemplate").value(false));
    }
}
