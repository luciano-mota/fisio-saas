package com.fisiosaas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

class LogoIntegrationTest extends IntegrationTest {

    static byte[] image(String format, int width, int height, Color color) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    private ResultActions upload(String owner, byte[] data, String declaredType) throws Exception {
        return mvc.perform(multipart("/api/v1/clinic/logo")
            .file(new MockMultipartFile("file", "logo.png", declaredType, data))
            .with(user(owner)).with(csrf()));
    }

    @Test
    void enviaPngEApareceNoPainelENaVitrine() throws Exception {
        byte[] png = image("png", 200, 120, new Color(29, 78, 216));
        upload(OWNER_A, png, "image/png").andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/clinic/logo").with(user(OWNER_A)))
            .andExpect(status().isOk())
            .andExpect(content().contentType("image/png"))
            .andExpect(content().bytes(png))
            .andExpect(header().exists("ETag"));
        mvc.perform(get("/api/v1/public/clinics/fisio-ana/logo"))
            .andExpect(status().isOk())
            .andExpect(content().bytes(png));

        // Versão da logo vai para o painel e para a vitrine (para não usar cache antigo).
        mvc.perform(get("/api/v1/clinic").with(user(OWNER_A))).andExpect(jsonPath("$.logoVersion").isNumber());
        mvc.perform(get("/api/v1/public/clinics/fisio-ana")).andExpect(jsonPath("$.logoVersion").isNumber());

        // Trocar a logo substitui a anterior.
        byte[] jpeg = image("jpg", 64, 64, Color.ORANGE);
        upload(OWNER_A, jpeg, "image/png").andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/clinic/logo").with(user(OWNER_A)))
            .andExpect(content().contentType("image/jpeg"))
            .andExpect(content().bytes(jpeg));

        mvc.perform(delete("/api/v1/clinic/logo").with(user(OWNER_A)).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/clinic/logo").with(user(OWNER_A))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/clinic").with(user(OWNER_A))).andExpect(jsonPath("$.logoVersion").doesNotExist());
    }

    @Test
    void recusaArquivoQueNaoEhPngOuJpgMesmoComTipoFalso() throws Exception {
        upload(OWNER_A, "<svg onload=alert(1)></svg>".getBytes(), "image/png")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Envie a logo em PNG ou JPG"));
        upload(OWNER_A, image("gif", 50, 50, Color.RED), "image/png").andExpect(status().isBadRequest());
        // Assinatura de PNG mas conteúdo corrompido.
        byte[] fake = Arrays.copyOf(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}, 200);
        upload(OWNER_A, fake, "image/png").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM clinic_logos", Integer.class)).isZero();
    }

    @Test
    void recusaLogoGrandeDemaisEmBytesOuPixels() throws Exception {
        byte[] heavy = Arrays.copyOf(image("png", 32, 32, Color.BLUE), 1024 * 1024 + 1);
        upload(OWNER_A, heavy, "image/png")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("A logo precisa ter até 1 MB"));
        upload(OWNER_A, image("png", 3000, 20, Color.BLUE), "image/png")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("A logo pode ter no máximo 2048 px de lado"));
        upload(OWNER_A, image("png", 8, 8, Color.BLUE), "image/png").andExpect(status().isBadRequest());
    }

    @Test
    void cadaClinicaSoMexeNaPropriaLogoEVitrineDesativadaNaoMostra() throws Exception {
        byte[] logoA = image("png", 100, 100, Color.GREEN);
        upload(OWNER_A, logoA, "image/png").andExpect(status().isNoContent());

        // B não tem logo e não enxerga a de A pelo painel.
        mvc.perform(get("/api/v1/clinic/logo").with(user(OWNER_B))).andExpect(status().isNotFound());
        // B remover a "própria" logo não apaga a de A.
        mvc.perform(delete("/api/v1/clinic/logo").with(user(OWNER_B)).with(csrf())).andExpect(status().isNoContent());
        // B enviar logo não troca a de A.
        upload(OWNER_B, image("png", 100, 100, Color.BLACK), "image/png").andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/public/clinics/fisio-ana/logo")).andExpect(content().bytes(logoA));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM clinic_logos", Integer.class)).isEqualTo(2);

        jdbc.update("UPDATE clinics SET public_page_enabled = FALSE WHERE id = ?", clinicA);
        mvc.perform(get("/api/v1/public/clinics/fisio-ana/logo")).andExpect(status().isNotFound());
    }

    @Test
    void colaboradoraVeMasNaoTrocaALogo() throws Exception {
        upload(OWNER_A, image("png", 100, 100, Color.GREEN), "image/png");
        mvc.perform(get("/api/v1/clinic/logo").with(user(STAFF_A))).andExpect(status().isOk());
        upload(STAFF_A, image("png", 100, 100, Color.RED), "image/png").andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/clinic/logo").with(user(STAFF_A)).with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    void envioSemLoginOuSemCsrfEhBloqueado() throws Exception {
        mvc.perform(multipart("/api/v1/clinic/logo")
                .file(new MockMultipartFile("file", "l.png", "image/png", image("png", 50, 50, Color.RED))))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/clinic/logo")).andExpect(status().isUnauthorized());
    }
}
