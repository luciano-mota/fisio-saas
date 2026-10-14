package com.fisiosaas.clinic;

import com.fisiosaas.identity.CurrentClinic;
import com.fisiosaas.publicsite.PublicClinics;
import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Logo da clínica. Aceita só PNG ou JPEG (o painel converte SVG/WebP para PNG antes de enviar), com o tipo
 * descoberto pela assinatura do arquivo, até 1 MB e 2048 px: nunca confia no Content-Type do cliente.
 */
@RestController
public class LogoController {
    static final int MAX_BYTES = 1024 * 1024;
    static final int MAX_SIDE = 2048;
    static final int MIN_SIDE = 16;

    private record Logo(String contentType, byte[] data, OffsetDateTime updatedAt) {}

    private final JdbcTemplate jdbc;
    private final CurrentClinic current;
    private final PublicClinics publicClinics;

    public LogoController(JdbcTemplate jdbc, CurrentClinic current, PublicClinics publicClinics) {
        this.jdbc = jdbc;
        this.current = current;
        this.publicClinics = publicClinics;
    }

    @PostMapping(path = "/api/v1/clinic/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void upload(Authentication auth, @RequestParam("file") MultipartFile file) throws IOException {
        UUID clinicId = current.requireOwner(auth).clinicId();
        if (file.isEmpty()) throw new IllegalArgumentException("Escolha uma imagem");
        if (file.getSize() > MAX_BYTES) throw new IllegalArgumentException("A logo precisa ter até 1 MB");
        byte[] data = file.getBytes();
        String type = detectType(data);
        int[] size = Images.dimensions(data, MIN_SIDE, MAX_SIDE, "logo");
        jdbc.update("""
            INSERT INTO clinic_logos(clinic_id, content_type, data, width, height, updated_at)
            VALUES (?, ?, ?, ?, ?, now())
            ON CONFLICT (clinic_id) DO UPDATE
               SET content_type = excluded.content_type, data = excluded.data, width = excluded.width,
                   height = excluded.height, updated_at = now()""",
            clinicId, type, data, size[0], size[1]);
    }

    @DeleteMapping("/api/v1/clinic/logo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(Authentication auth) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        jdbc.update("DELETE FROM clinic_logos WHERE clinic_id = ?", clinicId);
    }

    /** Logo da própria clínica, para o painel. */
    @GetMapping("/api/v1/clinic/logo")
    public ResponseEntity<byte[]> own(Authentication auth) {
        return serve(load(current.require(auth).clinicId()));
    }

    /** Logo na vitrine pública: só se a página da clínica estiver ativada. */
    @GetMapping("/api/v1/public/clinics/{slug}/logo")
    public ResponseEntity<byte[]> publicLogo(@PathVariable String slug) {
        return serve(load(publicClinics.requireEnabled(slug)));
    }

    private Logo load(UUID clinicId) {
        return jdbc.query("SELECT content_type, data, updated_at FROM clinic_logos WHERE clinic_id = ?",
                (rs, n) -> new Logo(rs.getString("content_type"), rs.getBytes("data"),
                    rs.getObject("updated_at", OffsetDateTime.class)),
                clinicId)
            .stream().findFirst().orElseThrow(NoSuchElementException::new);
    }

    /** O painel pede com ?v=versão; trocar a logo muda a versão e o navegador busca de novo. */
    private static ResponseEntity<byte[]> serve(Logo logo) {
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(logo.contentType()))
            .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
            .eTag(String.valueOf(logo.updatedAt().toInstant().toEpochMilli()))
            .body(logo.data());
    }

    /** Tipo pela assinatura (magic bytes), não pelo nome nem pelo Content-Type enviado. */
    static String detectType(byte[] data) {
        if (Images.isPng(data)) return "image/png";
        if (Images.isJpeg(data)) return "image/jpeg";
        throw new IllegalArgumentException("Envie a logo em PNG ou JPG");
    }
}
