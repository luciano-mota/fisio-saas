package com.fisiosaas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Administração da plataforma: só a equipe PhysioEvo, sem dados de pacientes, com motivo e histórico. */
class PlatformAdminIntegrationTest extends IntegrationTest {
    static final String ADMIN = "equipe@physioevo.test";
    static final RequestPostProcessor AS_ADMIN = SecurityMockMvcRequestPostProcessors.user(ADMIN).roles("PLATFORM_ADMIN");

    @BeforeEach
    void seedAdmin() {
        jdbc.execute("TRUNCATE platform_admins");
        jdbc.update("INSERT INTO platform_admins(id, email, password_hash) VALUES (?, ?, ?)",
            UUID.randomUUID(), ADMIN, passwords.encode(PASSWORD));
    }

    private LocalDate today() {
        return LocalDate.now(ZoneId.of("America/Fortaleza"));
    }

    private ResultActions access(UUID clinic, String body) throws Exception {
        return mvc.perform(post("/api/v1/admin/clinics/" + clinic + "/access").with(AS_ADMIN).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions newPatient(String asUser) throws Exception {
        return mvc.perform(post("/api/v1/patients").with(user(asUser)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"fullName\":\"Paciente Novo\",\"cpf\":\"" + cpf() + "\"}"));
    }

    @Test
    void soAEquipeDaPlataformaEntraNaAdministracao() throws Exception {
        mvc.perform(get("/api/v1/admin/clinics")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/clinics").with(user(OWNER_A))).andExpect(status().isForbidden());
        // Login de verdade do administrador: entra e o /auth/me diz o papel.
        var session = mvc.perform(post("/api/v1/auth/login").param("email", ADMIN).param("password", PASSWORD).with(csrf()))
            .andExpect(status().isOk()).andReturn().getResponse().getCookie("SESSION");
        mvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(jsonPath("$.role").value("PLATFORM_ADMIN"));
        mvc.perform(get("/api/v1/admin/clinics").cookie(session)).andExpect(status().isOk());
    }

    @Test
    void listaClinicasSemMostrarDadosDePacientes() throws Exception {
        create("/api/v1/patients", OWNER_A, "{\"fullName\":\"Maria Sigilosa\",\"cpf\":\"" + cpf() + "\"}");
        mvc.perform(get("/api/v1/admin/clinics").with(AS_ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.clinics", hasSize(2)))
            .andExpect(jsonPath("$.counts.ACTIVE").value(2))
            .andExpect(jsonPath("$.clinics[?(@.slug=='fisio-ana')].ownerEmail").value(OWNER_A))
            .andExpect(jsonPath("$.clinics[?(@.slug=='fisio-ana')].patients").value(1))
            .andExpect(content().string(not(containsString("Maria Sigilosa"))));
        mvc.perform(get("/api/v1/admin/clinics").param("q", "bia").with(AS_ADMIN))
            .andExpect(jsonPath("$.clinics", hasSize(1)));
        // O administrador não usa os endpoints de clínica (pacientes, agenda...).
        mvc.perform(get("/api/v1/patients").with(AS_ADMIN)).andExpect(status().isForbidden());
    }

    @Test
    void acaoExigeMotivoEFicaNoHistorico() throws Exception {
        access(clinicA, "{\"action\":\"SUSPEND\",\"reason\":\" \"}").andExpect(status().isBadRequest());
        access(clinicA, "{\"action\":\"TRIAL\",\"reason\":\"Teste estendido\"}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Informe por quantos dias vai o teste"));
        access(clinicA, "{\"action\":\"FREE\",\"until\":\"2020-01-01\",\"reason\":\"Parceria\"}").andExpect(status().isBadRequest());
        access(clinicA, "{\"action\":\"APAGAR\",\"reason\":\"Qualquer\"}").andExpect(status().isBadRequest());
        access(UUID.randomUUID(), "{\"action\":\"SUSPEND\",\"reason\":\"Não existe\"}").andExpect(status().isNotFound());

        access(clinicA, "{\"action\":\"FREE\",\"days\":60,\"reason\":\"Parceria com o conselho\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.clinic.status").value("FREE"))
            .andExpect(jsonPath("$.clinic.accessUntil").value(today().plusDays(60).toString()))
            .andExpect(jsonPath("$.history[0].action").value("FREE"))
            .andExpect(jsonPath("$.history[0].adminEmail").value(ADMIN))
            .andExpect(jsonPath("$.history[0].reason").value("Parceria com o conselho"));
        // Acesso gratuito sem prazo.
        access(clinicA, "{\"action\":\"FREE\",\"reason\":\"Clínica parceira\"}")
            .andExpect(jsonPath("$.clinic.accessUntil").doesNotExist())
            .andExpect(jsonPath("$.history", hasSize(2)));
        // A outra clínica não muda.
        assertThat(jdbc.queryForObject("SELECT status FROM clinics WHERE id = ?", String.class, clinicB)).isEqualTo("ACTIVE");
    }

    @Test
    void suspensaSoConsultaEOSiteFicaComoVitrineSemAgendamento() throws Exception {
        String antes = create("/api/v1/patients", OWNER_A, "{\"fullName\":\"Paciente Antigo\",\"cpf\":\"" + cpf() + "\"}");
        jdbc.update("UPDATE clinics SET booking_enabled = TRUE WHERE id = ?", clinicA);
        mvc.perform(get("/api/v1/public/clinics/fisio-ana")).andExpect(jsonPath("$.booking.enabled").value(true));

        access(clinicA, "{\"action\":\"SUSPEND\",\"reason\":\"Pagamento em atraso\"}").andExpect(status().isOk());

        newPatient(OWNER_A).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value(containsString("só para consulta")));
        // Consulta continua, com os dados guardados.
        mvc.perform(get("/api/v1/patients/" + antes).with(user(OWNER_A)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.fullName").value("Paciente Antigo"));
        // Trocar a própria senha continua liberado (aqui erra a senha atual: 400, não 403).
        mvc.perform(put("/api/v1/account/password").with(user(OWNER_A)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"errada-errada-errada\",\"newPassword\":\"outra-senha-bem-forte\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/clinic/access").with(user(STAFF_A)))
            .andExpect(jsonPath("$.readOnly").value(true)).andExpect(jsonPath("$.warning").value("SUSPENDED"));
        // Site no ar, mas sem agendamento.
        mvc.perform(get("/api/v1/public/clinics/fisio-ana"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.booking.enabled").value(false));
        // A outra clínica segue normal.
        newPatient(OWNER_B).andExpect(status().isCreated());

        access(clinicA, "{\"action\":\"ACTIVE\",\"reason\":\"Pagamento confirmado\"}").andExpect(status().isOk());
        newPatient(OWNER_A).andExpect(status().isCreated());
    }

    @Test
    void prazoVencidoViraSoConsultaSemPerderDados() throws Exception {
        String antes = create("/api/v1/patients", OWNER_A, "{\"fullName\":\"Paciente Guardado\",\"cpf\":\"" + cpf() + "\"}");
        access(clinicA, "{\"action\":\"TRIAL\",\"days\":3,\"reason\":\"Teste estendido\"}").andExpect(status().isOk());
        mvc.perform(get("/api/v1/clinic/access").with(user(OWNER_A)))
            .andExpect(jsonPath("$.warning").value("SOON")).andExpect(jsonPath("$.daysLeft").value(3))
            .andExpect(jsonPath("$.readOnly").value(false));

        jdbc.update("UPDATE clinics SET access_until = ? WHERE id = ?", today().minusDays(1), clinicA);
        mvc.perform(get("/api/v1/clinic/access").with(user(OWNER_A)))
            .andExpect(jsonPath("$.effectiveStatus").value("EXPIRED")).andExpect(jsonPath("$.warning").value("EXPIRED"))
            .andExpect(jsonPath("$.readOnly").value(true));
        newPatient(OWNER_A).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value(containsString("período de acesso terminou")));
        mvc.perform(get("/api/v1/patients/" + antes).with(user(OWNER_A))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/clinics").with(AS_ADMIN)).andExpect(jsonPath("$.counts.EXPIRED").value(1));
    }

    @Test
    void bloqueadaNaoEntraESiteSaiDoArAteLiberar() throws Exception {
        access(clinicA, "{\"action\":\"BLOCK\",\"reason\":\"Uso indevido\"}").andExpect(status().isOk());

        mvc.perform(post("/api/v1/auth/login").param("email", OWNER_A).param("password", PASSWORD).with(csrf()))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(containsString("Acesso bloqueado")));
        mvc.perform(get("/api/v1/patients").with(user(OWNER_A))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/public/clinics/fisio-ana")).andExpect(status().isNotFound());
        // Os dados continuam no banco.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE clinic_id = ?", Integer.class, clinicA)).isEqualTo(2);

        access(clinicA, "{\"action\":\"ACTIVE\",\"reason\":\"Situação resolvida\"}").andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/login").param("email", OWNER_A).param("password", PASSWORD).with(csrf()))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/public/clinics/fisio-ana")).andExpect(status().isOk());
    }
}
