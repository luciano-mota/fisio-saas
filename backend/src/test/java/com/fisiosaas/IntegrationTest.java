package com.fisiosaas;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base dos testes de integração: banco real (fisio_test) com duas clínicas, A e B.
 * A tem responsável (OWNER) e colaboradora (STAFF); B só responsável.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTest {
    protected static final String OWNER_A = "ana@fisio-a.test";
    protected static final String OWNER_B = "bia@fisio-b.test";
    protected static final String STAFF_A = "recepcao@fisio-a.test";
    protected static final String PASSWORD = "senha-forte-de-teste";

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PasswordEncoder passwords;
    @Autowired protected ObjectMapper json;

    protected UUID clinicA;
    protected UUID clinicB;

    @BeforeEach
    void seedClinics() {
        jdbc.execute("TRUNCATE clinics CASCADE");
        clinicA = clinic("fisio-ana", "Fisio Ana", true);
        clinicB = clinic("fisio-bia", "Fisio Bia", true);
        insertUser(clinicA, OWNER_A, "OWNER");
        insertUser(clinicA, STAFF_A, "STAFF");
        insertUser(clinicB, OWNER_B, "OWNER");
    }

    /** POST como o usuário informado, espera 201 e devolve o id criado. */
    protected String create(String url, String asUser, String body) throws Exception {
        String response = mvc.perform(post(url).with(user(asUser)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asText();
    }

    protected UUID clinic(String slug, String name, boolean publicPage) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO clinics(id, slug, display_name, public_page_enabled) VALUES (?, ?, ?, ?)",
            id, slug, name, publicPage);
        return id;
    }

    private static final AtomicInteger CPF_SEQ = new AtomicInteger(300_000_000);

    /** CPF válido e único para os testes (o cadastro de paciente exige CPF). */
    protected static String cpf() {
        String base = String.valueOf(CPF_SEQ.incrementAndGet());
        int d1 = check(base, 10);
        int d2 = check(base + d1, 11);
        return base + d1 + d2;
    }

    private static int check(String digits, int weight) {
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) sum += (digits.charAt(i) - '0') * (weight - i);
        int rest = (sum * 10) % 11;
        return rest == 10 ? 0 : rest;
    }

    /** Paciente antigo, cadastrado antes do CPF ser obrigatório (direto no banco). */
    protected String legacyPatient(UUID clinic, String name, String whatsapp) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO patients(id, clinic_id, full_name, search_name, whatsapp, active) VALUES (?, ?, ?, ?, ?, TRUE)",
            id, clinic, name, com.fisiosaas.patient.PatientController.searchKey(name), whatsapp);
        return id.toString();
    }

    protected void insertUser(UUID clinic, String email, String role) {
        jdbc.update("INSERT INTO users(id, clinic_id, email, password_hash, role) VALUES (?, ?, ?, ?, ?)",
            UUID.randomUUID(), clinic, email, passwords.encode(PASSWORD), role);
    }
}
