package com.fisiosaas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class PatientIntegrationTest extends IntegrationTest {
    static final String CPF_VALIDO = "529.982.247-25";
    static final String OUTRO_CPF_VALIDO = "111.444.777-35";

    @Test
    void cadastraEBuscaPorNomeSemAcentoNemMaiuscula() throws Exception {
        String joao = create("/api/v1/patients", OWNER_A, """
            {"fullName":"  João   da Silva ","cpf":"%s","whatsapp":"(98) 99999-1234",
             "birthDate":"1980-05-17","adminNotes":"Prefere manhã"}""".formatted(CPF_VALIDO));
        create("/api/v1/patients", OWNER_A, "{\"cpf\":\"" + cpf() + "\",\"fullName\":\"Joana Prado\"}");
        create("/api/v1/patients", OWNER_A, "{\"cpf\":\"" + cpf() + "\",\"fullName\":\"Maria Souza\"}");

        mvc.perform(get("/api/v1/patients/" + joao).with(user(OWNER_A)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fullName").value("João da Silva"))
            .andExpect(jsonPath("$.cpf").value("52998224725"))
            .andExpect(jsonPath("$.whatsapp").value("98999991234"))
            .andExpect(jsonPath("$.birthDate").value("1980-05-17"))
            .andExpect(jsonPath("$.adminNotes").value("Prefere manhã"));

        mvc.perform(get("/api/v1/patients").param("q", "JOAO").with(user(OWNER_A)))
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].fullName").value("João da Silva"));
        // Ordem alfabética sem acento: Joana antes de João.
        mvc.perform(get("/api/v1/patients").param("q", "jo").with(user(OWNER_A)))
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[0].fullName").value("Joana Prado"))
            .andExpect(jsonPath("$[1].fullName").value("João da Silva"));
        mvc.perform(get("/api/v1/patients").with(user(OWNER_A)))
            .andExpect(jsonPath("$", hasSize(3)));
        // Curinga do LIKE digitado na busca não lista todo mundo.
        mvc.perform(get("/api/v1/patients").param("q", "%").with(user(OWNER_A)))
            .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void cpfEhObrigatorioAoCadastrarEAoEditarPacienteAntigo() throws Exception {
        mvc.perform(post("/api/v1/patients").with(user(OWNER_A)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"Ana\",\"whatsapp\":\"98999991234\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Informe o CPF do paciente"));
        // Cadastro antigo sem CPF continua aparecendo, mas para editar precisa informar.
        String antiga = legacyPatient(clinicA, "Ana Antiga", "98999991234");
        mvc.perform(get("/api/v1/patients/" + antiga).with(user(OWNER_A))).andExpect(jsonPath("$.cpf").doesNotExist());
        mvc.perform(put("/api/v1/patients/" + antiga).with(user(OWNER_A)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"Ana Antiga\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/patients/" + antiga).with(user(OWNER_A)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"Ana Antiga\",\"cpf\":\"%s\"}".formatted(CPF_VALIDO)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.cpf").value("52998224725"));
    }

    @Test
    void cpfInvalidoOuRepetidoNaMesmaClinicaEhRecusado() throws Exception {
        mvc.perform(post("/api/v1/patients").with(user(OWNER_A)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"Ana\",\"cpf\":\"123.456.789-00\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("CPF inválido"));
        mvc.perform(post("/api/v1/patients").with(user(OWNER_A)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"Ana\",\"cpf\":\"111.111.111-11\"}"))
            .andExpect(status().isBadRequest());

        create("/api/v1/patients", OWNER_A, "{\"fullName\":\"Ana\",\"cpf\":\"%s\"}".formatted(CPF_VALIDO));
        String outra = create("/api/v1/patients", OWNER_A, "{\"cpf\":\"" + cpf() + "\",\"fullName\":\"Bia\"}");
        mvc.perform(post("/api/v1/patients").with(user(OWNER_A)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Ana 2\",\"cpf\":\"52998224725\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("Já existe paciente com este CPF: Ana"));
        mvc.perform(put("/api/v1/patients/" + outra).with(user(OWNER_A)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Bia\",\"cpf\":\"%s\"}".formatted(CPF_VALIDO)))
            .andExpect(status().isConflict());

        // Mesma pessoa pode ser paciente de outra clínica.
        create("/api/v1/patients", OWNER_B, "{\"fullName\":\"Ana\",\"cpf\":\"%s\"}".formatted(CPF_VALIDO));
    }

    @Test
    void validaWhatsappNascimentoENome() throws Exception {
        for (String body : new String[] {
            "{\"fullName\":\"Ana\",\"whatsapp\":\"9999-1234\"}",
            "{\"fullName\":\"Ana\",\"birthDate\":\"%s\"}".formatted(LocalDate.now().plusDays(1)),
            "{\"fullName\":\"Ana\",\"birthDate\":\"1850-01-01\"}",
            "{\"fullName\":\"   \"}"}) {
            mvc.perform(post("/api/v1/patients").with(user(OWNER_A)).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        }
    }

    @Test
    void clinicaNaoVeNemAlteraPacienteDeOutraClinica() throws Exception {
        String pacienteA = create("/api/v1/patients", OWNER_A, """
            {"fullName":"Maria Souza","cpf":"%s"}""".formatted(CPF_VALIDO));

        mvc.perform(get("/api/v1/patients").with(user(OWNER_B)))
            .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/v1/patients").param("q", "maria").param("includeInactive", "true").with(user(OWNER_B)))
            .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/v1/patients/" + pacienteA).with(user(OWNER_B)))
            .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/patients/" + pacienteA).with(user(OWNER_B)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"Invadido\"}"))
            .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/patients/" + pacienteA).with(user(OWNER_B)).with(csrf()))
            .andExpect(status().isNotFound());

        var row = jdbc.queryForMap("SELECT full_name, active FROM patients WHERE id = ?::uuid", pacienteA);
        assertThat(row.get("full_name")).isEqualTo("Maria Souza");
        assertThat(row.get("active")).isEqualTo(true);
    }

    @Test
    void colaboradoraConsultaMasNaoAltera() throws Exception {
        String paciente = create("/api/v1/patients", OWNER_A, "{\"cpf\":\"" + cpf() + "\",\"fullName\":\"Maria\"}");
        mvc.perform(get("/api/v1/patients").with(user(STAFF_A)))
            .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/v1/patients/" + paciente).with(user(STAFF_A))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/patients").with(user(STAFF_A)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"Nova\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/patients/" + paciente).with(user(STAFF_A)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"Outro\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/patients/" + paciente).with(user(STAFF_A)).with(csrf()))
            .andExpect(status().isForbidden());
    }

    @Test
    void desativarEscondeDaListaSemApagarEPodeReativar() throws Exception {
        String cpf = cpf();
        String paciente = create("/api/v1/patients", OWNER_A, "{\"cpf\":\"" + cpf + "\",\"fullName\":\"Maria\"}");
        mvc.perform(delete("/api/v1/patients/" + paciente).with(user(OWNER_A)).with(csrf()))
            .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/patients").with(user(OWNER_A))).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/v1/patients").param("includeInactive", "true").with(user(OWNER_A)))
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].active").value(false));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patients", Integer.class)).isEqualTo(1);

        mvc.perform(put("/api/v1/patients/" + paciente).with(user(OWNER_A)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"Maria\",\"cpf\":\"" + cpf + "\",\"active\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active").value(true));
    }
}
