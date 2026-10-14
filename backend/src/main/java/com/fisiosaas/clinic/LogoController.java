package com.fisiosaas.clinic;

import com.fisiosaas.identity.CurrentClinic;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
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

    public LogoController(JdbcTemplate jdbc, CurrentClinic current) {
        this.jdbc = jdbc;
        this.current = current;
    }

    @PostMapping(path = "/api/v1/clinic/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void upload(Authentication auth, @RequestParam("file") MultipartFile file) throws IOException {
        UUID clinicId = current.requireOwner(auth).clinicId();
        if (file.isEmpty()) throw new IllegalArgumentException("Escolha uma imagem");
        if (file.getSize() > MAX_BYTES) throw new IllegalArgumentException("A logo precisa ter até 1 MB");
        byte[] data = file.getBytes();
        String type = detectType(data);
        int[] size = dimensions(data);
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
        UUID clinicId = jdbc.query("SELECT id FROM clinics WHERE slug = ? AND public_page_enabled",
                (rs, n) -> rs.getObject("id", UUID.class), slug.toLowerCase(Locale.ROOT))
            .stream().findFirst().orElseThrow(NoSuchElementException::new);
        return serve(load(clinicId));
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
        if (data.length > 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
            return "image/png";
        }
        if (data.length > 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        throw new IllegalArgumentException("Envie a logo em PNG ou JPG");
    }

    /** Lê só o cabeçalho da imagem (sem decodificar os pixels) para recusar imagens enormes ou corrompidas. */
    static int[] dimensions(byte[] data) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("Imagem inválida");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width > MAX_SIDE || height > MAX_SIDE) {
                    throw new IllegalArgumentException("A logo pode ter no máximo " + MAX_SIDE + " px de lado");
                }
                if (width < MIN_SIDE || height < MIN_SIDE) throw new IllegalArgumentException("Logo pequena demais");
                return new int[] {width, height};
            } finally {
                reader.dispose();
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("Imagem inválida");
        }
    }
}
