package com.fisiosaas;

import static com.fisiosaas.LogoIntegrationTest.image;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

/** Site profissional: fotos, depoimentos, perguntas frequentes e textos de confiança, com isolamento entre clínicas. */
class SiteContentIntegrationTest extends IntegrationTest {

    private ResultActions upload(String owner, byte[] data, String kind) throws Exception {
        return mvc.perform(multipart("/api/v1/clinic/photos")
            .file(new MockMultipartFile("file", "foto.jpg", "image/jpeg", data))
            .param("kind", kind)
            .with(user(owner)).with(csrf()));
    }

    private String uploadId(String owner, byte[] data, String kind) throws Exception {
        String body = upload(owner, data, kind).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asText();
    }

    private static byte[] jpeg(Color color) throws Exception {
        return image("jpg", 800, 600, color);
    }

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                               String asUser, String body) throws Exception {
        return mvc.perform(request.with(user(asUser)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // ---------- Fotos ----------

    @Test
    void capaEGaleriaAparecemNoSiteETrocarCapaSubstitui() throws Exception {
        String cover = uploadId(OWNER_A, jpeg(Color.BLUE), "COVER");
        String first = uploadId(OWNER_A, jpeg(Color.RED), "GALLERY");
        String second = uploadId(OWNER_A, jpeg(Color.GREEN), "GALLERY");

        mvc.perform(get("/api/v1/clinic/photos").with(user(OWNER_A)))
            .andExpect(jsonPath("$", hasSize(3)))
            .andExpect(jsonPath("$[0].id").value(cover))
            .andExpect(jsonPath("$[1].id").value(first))
            .andExpect(jsonPath("$[2].id").value(second));
        mvc.perform(get("/api/v1/public/clinics/fisio-ana"))
            .andExpect(jsonPath("$.site.cover.id").value(cover))
            .andExpect(jsonPath("$.site.cover.width").value(800))
            .andExpect(jsonPath("$.site.gallery", hasSize(2)));
        mvc.perform(get("/api/v1/public/clinics/fisio-ana/photos/" + cover))
            .andExpect(status().isOk())
            .andExpect(content().contentType("image/jpeg"))
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("public")));

        // Legenda e ordem.
        send(put("/api/v1/clinic/photos/" + second), OWNER_A, "{\"caption\":\"  Sala de pilates  \"}")
            .andExpect(jsonPath("$.caption").value("Sala de pilates"));
        send(post("/api/v1/clinic/photos/" + second + "/move"), OWNER_A, "{\"direction\":\"up\"}")
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/public/clinics/fisio-ana"))
            .andExpect(jsonPath("$.site.gallery[0].id").value(second))
            .andExpect(jsonPath("$.site.gallery[0].caption").value("Sala de pilates"));
        // Subir o primeiro não faz nada (já está no topo).
        send(post("/api/v1/clinic/photos/" + second + "/move"), OWNER_A, "{\"direction\":\"up\"}")
            .andExpect(status().isNoContent());
        send(post("/api/v1/clinic/photos/" + second + "/move"), OWNER_A, "{\"direction\":\"sideways\"}")
            .andExpect(status().isBadRequest());

        // Nova capa substitui a antiga: nunca duas capas.
        String newCover = uploadId(OWNER_A, jpeg(Color.ORANGE), "COVER");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM clinic_photos WHERE kind = 'COVER'", Integer.class)).isEqualTo(1);
        mvc.perform(get("/api/v1/public/clinics/fisio-ana")).andExpect(jsonPath("$.site.cover.id").value(newCover));
        mvc.perform(get("/api/v1/public/clinics/fisio-ana/photos/" + cover)).andExpect(status().isNotFound());
    }

    @Test
    void removerFotoApagaDeVerdade() throws Exception {
        String id = uploadId(OWNER_A, jpeg(Color.RED), "GALLERY");
        mvc.perform(delete("/api/v1/clinic/photos/" + id).with(user(OWNER_A)).with(csrf()))
            .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM clinic_photos", Integer.class)).isZero();
        mvc.perform(get("/api/v1/clinic/photos/" + id + "/image").with(user(OWNER_A))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/clinic/photos/" + id).with(user(OWNER_A)).with(csrf()))
            .andExpect(status().isNotFound());
    }

    @Test
    void fotoPublicadaSaiSemMetadadosEXIF() throws Exception {
        byte[] plain = jpeg(Color.BLUE);
        // Insere um segmento APP1 (EXIF) com "localização" logo depois do início do arquivo.
        byte[] payload = "Exif\0\0GPS-LAT-2.53-LON-44.30".getBytes(StandardCharsets.ISO_8859_1);
        var withExif = new ByteArrayOutputStream();
        withExif.write(plain, 0, 2);
        withExif.write(0xFF);
        withExif.write(0xE1);
        withExif.write((payload.length + 2) >> 8);
        withExif.write((payload.length + 2) & 0xFF);
        withExif.write(payload);
        withExif.write(plain, 2, plain.length - 2);

        String id = uploadId(OWNER_A, withExif.toByteArray(), "GALLERY");
        byte[] served = mvc.perform(get("/api/v1/public/clinics/fisio-ana/photos/" + id))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(served, StandardCharsets.ISO_8859_1)).doesNotContain("GPS-LAT");
        assertThat(ImageIO.read(new ByteArrayInputStream(served))).isNotNull();
    }

    @Test
    void recusaFotoForaDoPadraoEGaleriaPassandoDeSeis() throws Exception {
        upload(OWNER_A, image("png", 800, 600, Color.RED), "GALLERY")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Envie a foto em JPG"));
        upload(OWNER_A, "<svg onload=alert(1)>".getBytes(), "GALLERY").andExpect(status().isBadRequest());
        upload(OWNER_A, image("jpg", 200, 200, Color.RED), "GALLERY")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Foto pequena demais"));
        upload(OWNER_A, image("jpg", 3000, 400, Color.RED), "GALLERY")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("A foto pode ter no máximo 2400 px de lado"));
        upload(OWNER_A, jpeg(Color.RED), "PERFIL").andExpect(status().isBadRequest());

        for (int i = 0; i < 6; i++) upload(OWNER_A, jpeg(Color.RED), "GALLERY").andExpect(status().isCreated());
        upload(OWNER_A, jpeg(Color.RED), "GALLERY")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("A galeria tem no máximo 6 fotos. Remova uma antes."));
        // A capa não conta no limite da galeria.
        upload(OWNER_A, jpeg(Color.RED), "COVER").andExpect(status().isCreated());
    }

    @Test
    void cadaClinicaSoMexeNasPropriasFotos() throws Exception {
        String photoA = uploadId(OWNER_A, jpeg(Color.RED), "GALLERY");
        uploadId(OWNER_B, jpeg(Color.BLUE), "GALLERY");

        mvc.perform(get("/api/v1/clinic/photos").with(user(OWNER_B))).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/v1/clinic/photos/" + photoA + "/image").with(user(OWNER_B))).andExpect(status().isNotFound());
        send(put("/api/v1/clinic/photos/" + photoA), OWNER_B, "{\"caption\":\"invasão\"}").andExpect(status().isNotFound());
        send(post("/api/v1/clinic/photos/" + photoA + "/move"), OWNER_B, "{\"direction\":\"up\"}")
            .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/clinic/photos/" + photoA).with(user(OWNER_B)).with(csrf()))
            .andExpect(status().isNotFound());
        // Pelo endereço público da outra clínica também não aparece.
        mvc.perform(get("/api/v1/public/clinics/fisio-bia/photos/" + photoA)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/public/clinics/fisio-ana/photos/" + photoA)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT caption FROM clinic_photos WHERE id = ?::uuid", String.class, photoA)).isNull();

        // Site desativado não serve as fotos.
        jdbc.update("UPDATE clinics SET public_page_enabled = FALSE WHERE id = ?", clinicA);
        mvc.perform(get("/api/v1/public/clinics/fisio-ana/photos/" + photoA)).andExpect(status().isNotFound());
    }

    @Test
    void colaboradoraVeAsFotosMasNaoAltera() throws Exception {
        String id = uploadId(OWNER_A, jpeg(Color.RED), "GALLERY");
        mvc.perform(get("/api/v1/clinic/photos").with(user(STAFF_A))).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/v1/clinic/photos/" + id + "/image").with(user(STAFF_A))).andExpect(status().isOk());
        upload(STAFF_A, jpeg(Color.RED), "GALLERY").andExpect(status().isForbidden());
        send(put("/api/v1/clinic/photos/" + id), STAFF_A, "{\"caption\":\"x\"}").andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/clinic/photos/" + id).with(user(STAFF_A)).with(csrf())).andExpect(status().isForbidden());
    }

    // ---------- Depoimentos ----------

    @Test
    void depoimentoExigeAutorizacaoESoApareceComASecaoLigada() throws Exception {
        send(post("/api/v1/clinic/testimonials"), OWNER_A, "{\"authorName\":\"Maria S.\",\"text\":\"Voltei a andar sem dor.\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Confirme que o paciente autorizou por escrito a publicação do depoimento"));

        String id = create("/api/v1/clinic/testimonials", OWNER_A,
            "{\"authorName\":\"Maria S.\",\"text\":\"Voltei a andar sem dor.\",\"consent\":true}");
        create("/api/v1/clinic/testimonials", OWNER_A, "{\"authorName\":\"João\",\"text\":\"Atendimento ótimo.\",\"consent\":true}");
        mvc.perform(get("/api/v1/clinic/testimonials").with(user(OWNER_A)))
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[0].consentConfirmedAt").isNotEmpty());

        // Seção desligada por padrão: nada no site.
        mvc.perform(get("/api/v1/public/clinics/fisio-ana")).andExpect(jsonPath("$.site.testimonials", hasSize(0)));
        jdbc.update("UPDATE clinics SET site_show_testimonials = TRUE WHERE id = ?", clinicA);
        mvc.perform(get("/api/v1/public/clinics/fisio-ana"))
            .andExpect(jsonPath("$.site.testimonials", hasSize(2)))
            .andExpect(jsonPath("$.site.testimonials[0].authorName").value("Maria S."));

        // Remover desativa (fica no banco) e some do site.
        mvc.perform(delete("/api/v1/clinic/testimonials/" + id).with(user(OWNER_A)).with(csrf()))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/public/clinics/fisio-ana"))
            .andExpect(jsonPath("$.site.testimonials", hasSize(1)))
            .andExpect(jsonPath("$.site.testimonials[0].authorName").value("João"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM clinic_testimonials", Integer.class)).isEqualTo(2);
        send(put("/api/v1/clinic/testimonials/" + id), OWNER_A, "{\"authorName\":\"X\",\"text\":\"Y\"}")
            .andExpect(status().isNotFound());
    }

    @Test
    void cadaClinicaSoMexeNosPropriosDepoimentosEPerguntas() throws Exception {
        String testimonial = create("/api/v1/clinic/testimonials", OWNER_A,
            "{\"authorName\":\"Maria\",\"text\":\"Ótimo.\",\"consent\":true}");
        String faq = create("/api/v1/clinic/faqs", OWNER_A,
            "{\"question\":\"Atende em casa?\",\"answer\":\"Sim, em toda a cidade.\"}");

        mvc.perform(get("/api/v1/clinic/testimonials").with(user(OWNER_B))).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/v1/clinic/faqs").with(user(OWNER_B))).andExpect(jsonPath("$", hasSize(0)));
        for (String url : new String[] {"/api/v1/clinic/testimonials/" + testimonial, "/api/v1/clinic/faqs/" + faq}) {
            send(put(url), OWNER_B,
                "{\"authorName\":\"B\",\"text\":\"B\",\"question\":\"B?\",\"answer\":\"B\"}").andExpect(status().isNotFound());
            send(post(url + "/move"), OWNER_B, "{\"direction\":\"down\"}").andExpect(status().isNotFound());
            mvc.perform(delete(url).with(user(OWNER_B)).with(csrf())).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/v1/clinic/testimonials").with(user(OWNER_A)))
            .andExpect(jsonPath("$[0].authorName").value("Maria"));
        mvc.perform(get("/api/v1/clinic/faqs").with(user(OWNER_A)))
            .andExpect(jsonPath("$[0].question").value("Atende em casa?"));
        // Conteúdo de A não vaza para o site de B.
        jdbc.update("UPDATE clinics SET site_show_testimonials = TRUE");
        mvc.perform(get("/api/v1/public/clinics/fisio-bia"))
            .andExpect(jsonPath("$.site.testimonials", hasSize(0)))
            .andExpect(jsonPath("$.site.faqs", hasSize(0)));
    }

    @Test
    void colaboradoraNaoAlteraDepoimentosNemPerguntas() throws Exception {
        send(post("/api/v1/clinic/testimonials"), STAFF_A, "{\"authorName\":\"M\",\"text\":\"T\",\"consent\":true}")
            .andExpect(status().isForbidden());
        send(post("/api/v1/clinic/faqs"), STAFF_A, "{\"question\":\"Q?\",\"answer\":\"A\"}")
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/clinic/faqs").with(user(STAFF_A))).andExpect(status().isOk());
    }

    // ---------- Perguntas frequentes ----------

    @Test
    void perguntasAparecemNoSiteNaOrdemEscolhida() throws Exception {
        String first = create("/api/v1/clinic/faqs", OWNER_A, "{\"question\":\"Precisa de pedido médico?\",\"answer\":\"Não.\"}");
        String second = create("/api/v1/clinic/faqs", OWNER_A, "{\"question\":\"Atende em casa?\",\"answer\":\"Sim.\"}");
        send(post("/api/v1/clinic/faqs/" + first + "/move"), OWNER_A, "{\"direction\":\"down\"}")
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/public/clinics/fisio-ana"))
            .andExpect(jsonPath("$.site.faqs[0].question").value("Atende em casa?"))
            .andExpect(jsonPath("$.site.faqs[1].question").value("Precisa de pedido médico?"));

        send(put("/api/v1/clinic/faqs/" + second), OWNER_A, "{\"question\":\"Atende em domicílio?\",\"answer\":\"Sim.\"}")
            .andExpect(jsonPath("$.question").value("Atende em domicílio?"));
        send(post("/api/v1/clinic/faqs"), OWNER_A, "{\"question\":\"\",\"answer\":\"x\"}").andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/clinic/faqs/" + second).with(user(OWNER_A)).with(csrf()))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/public/clinics/fisio-ana")).andExpect(jsonPath("$.site.faqs", hasSize(1)));
    }

    // ---------- Textos de confiança ----------

    @Test
    void textosDeConfiancaVaoParaOSiteENuloMantem() throws Exception {
        send(put("/api/v1/clinic"), OWNER_A, """
            {"displayName":"Fisio Ana","publicPageEnabled":true,
             "siteEducation":"Fisioterapeuta (UFMA), especialista em Pilates clínico",
             "siteHours":"Seg a sex, 7h às 19h","siteServiceArea":"Centro e Cohama","siteShowMap":false}""")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.siteShowMap").value(false))
            .andExpect(jsonPath("$.siteShowTestimonials").value(false));
        mvc.perform(get("/api/v1/public/clinics/fisio-ana"))
            .andExpect(jsonPath("$.site.education").value("Fisioterapeuta (UFMA), especialista em Pilates clínico"))
            .andExpect(jsonPath("$.site.hours").value("Seg a sex, 7h às 19h"))
            .andExpect(jsonPath("$.site.serviceArea").value("Centro e Cohama"))
            .andExpect(jsonPath("$.site.showMap").value(false))
            .andExpect(jsonPath("$.site.gallery", hasSize(0)));

        // Sem os campos mantém; em branco apaga.
        send(put("/api/v1/clinic"), OWNER_A, "{\"displayName\":\"Fisio Ana\",\"publicPageEnabled\":true,\"siteHours\":\" \"}")
            .andExpect(jsonPath("$.siteEducation").value("Fisioterapeuta (UFMA), especialista em Pilates clínico"))
            .andExpect(jsonPath("$.siteHours").doesNotExist())
            .andExpect(jsonPath("$.siteShowMap").value(false));
        send(put("/api/v1/clinic"), OWNER_A, "{\"displayName\":\"Fisio Ana\",\"siteHours\":\"" + "x".repeat(301) + "\"}")
            .andExpect(status().isBadRequest());
        // B não foi afetada.
        mvc.perform(get("/api/v1/public/clinics/fisio-bia"))
            .andExpect(jsonPath("$.site.education").doesNotExist())
            .andExpect(jsonPath("$.site.showMap").value(true));
    }
}
