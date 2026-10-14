package com.fisiosaas.clinic;

import com.fisiosaas.identity.CurrentClinic;
import com.fisiosaas.publicsite.PublicClinics;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Fotos do site: uma capa (trocar substitui) e até 6 na galeria. Só JPEG: o painel recomprime no navegador
 * (lado máx. 1600 px) e aqui o servidor confere assinatura, tamanho e dimensões e tira os metadados (EXIF/GPS).
 * Remover apaga de verdade (exceção combinada à regra "nada é apagado": foto não é histórico e é dado pessoal).
 */
@RestController
public class PhotoController {
    static final int MAX_BYTES = 800 * 1024;
    static final int MAX_SIDE = 2400;
    static final int MIN_SIDE = 320;
    static final int MAX_GALLERY = 6;
    private static final String TABLE = "clinic_photos";
    private static final String GALLERY = "kind = 'GALLERY'";

    public record PhotoView(UUID id, String kind, String caption, int width, int height, int position) {}

    public record PhotoUpdate(@Size(max = 140) String caption) {}

    public record Move(@NotNull @Pattern(regexp = "^(up|down)$", message = "use up ou down") String direction) {}

    private record Image(byte[] data) {}

    static final RowMapper<PhotoView> MAPPER = (rs, n) -> new PhotoView(rs.getObject("id", UUID.class),
        rs.getString("kind"), rs.getString("caption"), rs.getInt("width"), rs.getInt("height"), rs.getInt("position"));

    private final JdbcTemplate jdbc;
    private final CurrentClinic current;
    private final PublicClinics publicClinics;

    public PhotoController(JdbcTemplate jdbc, CurrentClinic current, PublicClinics publicClinics) {
        this.jdbc = jdbc;
        this.current = current;
        this.publicClinics = publicClinics;
    }

    @GetMapping("/api/v1/clinic/photos")
    public List<PhotoView> list(Authentication auth) {
        return list(jdbc, current.require(auth).clinicId());
    }

    /** Capa primeiro, depois a galeria na ordem escolhida. Também usado pela vitrine pública. */
    public static List<PhotoView> list(JdbcTemplate jdbc, UUID clinicId) {
        return jdbc.query("""
            SELECT id, kind, caption, width, height, position FROM clinic_photos WHERE clinic_id = ?
             ORDER BY kind = 'COVER' DESC, position, created_at, id""", MAPPER, clinicId);
    }

    @PostMapping(path = "/api/v1/clinic/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public PhotoView upload(Authentication auth, @RequestParam("file") MultipartFile file,
                            @RequestParam("kind") String kind,
                            @RequestParam(value = "caption", required = false) String caption) throws IOException {
        UUID clinicId = current.requireOwner(auth).clinicId();
        if (!"COVER".equals(kind) && !"GALLERY".equals(kind)) throw new IllegalArgumentException("Tipo de foto inválido");
        if (caption != null && caption.length() > 140) throw new IllegalArgumentException("Legenda com até 140 caracteres");
        if (file.isEmpty()) throw new IllegalArgumentException("Escolha uma foto");
        if (file.getSize() > MAX_BYTES) throw new IllegalArgumentException("A foto precisa ter até 800 KB");
        byte[] data = file.getBytes();
        if (!Images.isJpeg(data)) throw new IllegalArgumentException("Envie a foto em JPG");
        int[] size = Images.dimensions(data, MIN_SIDE, MAX_SIDE, "foto");
        byte[] clean = Images.stripJpegMetadata(data);

        // Trava a clínica: dois envios ao mesmo tempo não passam do limite da galeria nem criam duas capas.
        jdbc.queryForObject("SELECT id FROM clinics WHERE id = ? FOR UPDATE", UUID.class, clinicId);
        if ("COVER".equals(kind)) {
            jdbc.update("DELETE FROM clinic_photos WHERE clinic_id = ? AND kind = 'COVER'", clinicId);
        } else {
            Integer count = jdbc.queryForObject("SELECT count(*) FROM clinic_photos WHERE clinic_id = ? AND " + GALLERY,
                Integer.class, clinicId);
            if (count >= MAX_GALLERY) {
                throw new IllegalArgumentException("A galeria tem no máximo " + MAX_GALLERY + " fotos. Remova uma antes.");
            }
        }
        UUID id = UUID.randomUUID();
        int position = "COVER".equals(kind) ? 0 : Positions.next(jdbc, TABLE, GALLERY, clinicId);
        jdbc.update("""
            INSERT INTO clinic_photos(id, clinic_id, kind, content_type, data, width, height, caption, position)
            VALUES (?, ?, ?, 'image/jpeg', ?, ?, ?, ?, ?)""",
            id, clinicId, kind, clean, size[0], size[1], ClinicController.blankToNull(caption), position);
        return find(clinicId, id);
    }

    @PutMapping("/api/v1/clinic/photos/{id}")
    public PhotoView update(Authentication auth, @PathVariable UUID id, @Valid @RequestBody PhotoUpdate body) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        find(clinicId, id);
        jdbc.update("UPDATE clinic_photos SET caption = ? WHERE id = ? AND clinic_id = ?",
            ClinicController.blankToNull(body.caption()), id, clinicId);
        return find(clinicId, id);
    }

    @PostMapping("/api/v1/clinic/photos/{id}/move")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void move(Authentication auth, @PathVariable UUID id, @Valid @RequestBody Move body) {
        Positions.move(jdbc, TABLE, GALLERY, current.requireOwner(auth).clinicId(), id, body.direction());
    }

    @DeleteMapping("/api/v1/clinic/photos/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(Authentication auth, @PathVariable UUID id) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        if (jdbc.update("DELETE FROM clinic_photos WHERE id = ? AND clinic_id = ?", id, clinicId) == 0) {
            throw new NoSuchElementException();
        }
    }

    @GetMapping("/api/v1/clinic/photos/{id}/image")
    public ResponseEntity<byte[]> own(Authentication auth, @PathVariable UUID id) {
        return serve(image(current.require(auth).clinicId(), id), CacheControl.maxAge(Duration.ofDays(7)).cachePrivate());
    }

    /** Foto no site público: só se a página estiver ativada e a foto for daquela clínica. */
    @GetMapping("/api/v1/public/clinics/{slug}/photos/{id}")
    public ResponseEntity<byte[]> publicPhoto(@PathVariable String slug, @PathVariable UUID id) {
        return serve(image(publicClinics.requireEnabled(slug), id), CacheControl.maxAge(Duration.ofDays(7)).cachePublic());
    }

    private PhotoView find(UUID clinicId, UUID id) {
        return jdbc.query("SELECT id, kind, caption, width, height, position FROM clinic_photos WHERE id = ? AND clinic_id = ?",
                MAPPER, id, clinicId)
            .stream().findFirst().orElseThrow(NoSuchElementException::new);
    }

    private Image image(UUID clinicId, UUID id) {
        return jdbc.query("SELECT data FROM clinic_photos WHERE id = ? AND clinic_id = ?",
                (rs, n) -> new Image(rs.getBytes("data")), id, clinicId)
            .stream().findFirst().orElseThrow(NoSuchElementException::new);
    }

    /** O conteúdo de um id nunca muda (trocar a foto gera outro id), então dá para guardar em cache. */
    private static ResponseEntity<byte[]> serve(Image image, CacheControl cache) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(cache).body(image.data());
    }
}
