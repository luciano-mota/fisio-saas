package com.fisiosaas.clinic;

import com.fisiosaas.identity.CurrentClinic;
import com.fisiosaas.publicsite.PublicClinics;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
 * Equipe da clínica: profissionais de várias áreas da saúde (fisioterapia, nutrição, psicologia…), com registro no
 * conselho, apresentação e foto opcional. Aparecem no site na seção "Nossa equipe" (com slides das fotos, se a
 * clínica quiser) e podem ser indicados em cada serviço.
 */
@RestController
public class ProfessionalController {
    private static final String TABLE = "professionals";
    private static final String ALL = "TRUE";

    public record ProfessionalRequest(@NotBlank(message = "Informe o nome") @Size(max = 120) String fullName,
                                      @NotBlank(message = "Informe a profissão") @Size(max = 60) String profession,
                                      @Size(max = 40) String council, @Size(max = 600) String bio,
                                      Boolean showOnSite, Boolean active) {}

    public record ProfessionalView(UUID id, String fullName, String profession, String council, String bio,
                                   boolean showOnSite, boolean active, int position, Long photoVersion) {}

    /** Seção "Nossa equipe" no site e as fotos (slides). */
    public record TeamSettings(boolean showTeam, boolean showPhotos) {}

    public record Move(@NotNull @Pattern(regexp = "^(up|down)$", message = "use up ou down") String direction) {}

    static final RowMapper<ProfessionalView> MAPPER = (rs, n) -> new ProfessionalView(rs.getObject("id", UUID.class),
        rs.getString("full_name"), rs.getString("profession"), rs.getString("council"), rs.getString("bio"),
        rs.getBoolean("show_on_site"), rs.getBoolean("active"), rs.getInt("position"), (Long) rs.getObject("photo_version"));
    private static final String COLUMNS = "id, full_name, profession, council, bio, show_on_site, active, position, photo_version";

    private final JdbcTemplate jdbc;
    private final CurrentClinic current;
    private final PublicClinics publicClinics;

    public ProfessionalController(JdbcTemplate jdbc, CurrentClinic current, PublicClinics publicClinics) {
        this.jdbc = jdbc;
        this.current = current;
        this.publicClinics = publicClinics;
    }

    @GetMapping("/api/v1/professionals")
    public List<ProfessionalView> list(Authentication auth) {
        return jdbc.query("SELECT " + COLUMNS + " FROM professionals WHERE clinic_id = ? ORDER BY active DESC, position, full_name",
            MAPPER, current.require(auth).clinicId());
    }

    @PostMapping("/api/v1/professionals")
    @ResponseStatus(HttpStatus.CREATED)
    public ProfessionalView create(Authentication auth, @Valid @RequestBody ProfessionalRequest body) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO professionals(id, clinic_id, full_name, profession, council, bio, show_on_site, position)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            id, clinicId, clean(body.fullName()), clean(body.profession()), ClinicController.blankToNull(body.council()),
            ClinicController.blankToNull(body.bio()), !Boolean.FALSE.equals(body.showOnSite()),
            Positions.next(jdbc, TABLE, ALL, clinicId));
        return find(clinicId, id);
    }

    @PutMapping("/api/v1/professionals/{id}")
    public ProfessionalView update(Authentication auth, @PathVariable UUID id, @Valid @RequestBody ProfessionalRequest body) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        ProfessionalView old = find(clinicId, id);
        jdbc.update("""
            UPDATE professionals SET full_name = ?, profession = ?, council = ?, bio = ?, show_on_site = ?, active = ?,
                   updated_at = now() WHERE id = ? AND clinic_id = ?""",
            clean(body.fullName()), clean(body.profession()), ClinicController.blankToNull(body.council()),
            ClinicController.blankToNull(body.bio()), body.showOnSite() == null ? old.showOnSite() : body.showOnSite(),
            body.active() == null ? old.active() : body.active(), id, clinicId);
        return find(clinicId, id);
    }

    /** Não apaga: desativa (sai do site; os serviços continuam no histórico). */
    @DeleteMapping("/api/v1/professionals/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(Authentication auth, @PathVariable UUID id) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        find(clinicId, id);
        jdbc.update("UPDATE professionals SET active = FALSE, updated_at = now() WHERE id = ? AND clinic_id = ?", id, clinicId);
    }

    @PostMapping("/api/v1/professionals/{id}/move")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void move(Authentication auth, @PathVariable UUID id, @Valid @RequestBody Move body) {
        Positions.move(jdbc, TABLE, ALL, current.requireOwner(auth).clinicId(), id, body.direction());
    }

    /** Foto (opcional): JPG já reduzido no navegador; o servidor confere e tira os metadados (EXIF/GPS). */
    @PostMapping(path = "/api/v1/professionals/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProfessionalView photo(Authentication auth, @PathVariable UUID id, @RequestParam("file") MultipartFile file) throws IOException {
        UUID clinicId = current.requireOwner(auth).clinicId();
        find(clinicId, id);
        if (file.isEmpty()) throw new IllegalArgumentException("Escolha uma foto");
        if (file.getSize() > PhotoController.MAX_BYTES) throw new IllegalArgumentException("A foto precisa ter até 800 KB");
        byte[] data = file.getBytes();
        if (!Images.isJpeg(data)) throw new IllegalArgumentException("Envie a foto em JPG");
        int[] size = Images.dimensions(data, PhotoController.MIN_SIDE, PhotoController.MAX_SIDE, "foto");
        jdbc.update("""
            UPDATE professionals SET photo = ?, photo_width = ?, photo_height = ?, photo_version = ?, updated_at = now()
             WHERE id = ? AND clinic_id = ?""",
            Images.stripJpegMetadata(data), size[0], size[1], System.currentTimeMillis(), id, clinicId);
        return find(clinicId, id);
    }

    /** Remover a foto apaga de verdade (é imagem de pessoa). */
    @DeleteMapping("/api/v1/professionals/{id}/photo")
    public ProfessionalView removePhoto(Authentication auth, @PathVariable UUID id) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        find(clinicId, id);
        jdbc.update("""
            UPDATE professionals SET photo = NULL, photo_width = NULL, photo_height = NULL, photo_version = NULL, updated_at = now()
             WHERE id = ? AND clinic_id = ?""", id, clinicId);
        return find(clinicId, id);
    }

    @GetMapping("/api/v1/professionals/{id}/photo")
    public ResponseEntity<byte[]> ownPhoto(Authentication auth, @PathVariable UUID id) {
        return serve(photoOf(current.require(auth).clinicId(), id, false), CacheControl.maxAge(Duration.ofDays(7)).cachePrivate());
    }

    /** Foto no site: só de profissional ativo, visível no site e com as fotos da equipe ligadas. */
    @GetMapping("/api/v1/public/clinics/{slug}/professionals/{id}/photo")
    public ResponseEntity<byte[]> publicPhoto(@PathVariable String slug, @PathVariable UUID id) {
        return serve(photoOf(publicClinics.requireEnabled(slug), id, true), CacheControl.maxAge(Duration.ofDays(7)).cachePublic());
    }

    @GetMapping("/api/v1/professionals/settings")
    public TeamSettings settings(Authentication auth) {
        return settings(jdbc, current.require(auth).clinicId());
    }

    @PutMapping("/api/v1/professionals/settings")
    public TeamSettings saveSettings(Authentication auth, @RequestBody TeamSettings body) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        jdbc.update("UPDATE clinics SET site_show_team = ?, site_team_photos = ? WHERE id = ?", body.showTeam(), body.showPhotos(), clinicId);
        return settings(jdbc, clinicId);
    }

    public static TeamSettings settings(JdbcTemplate jdbc, UUID clinicId) {
        return jdbc.queryForObject("SELECT site_show_team, site_team_photos FROM clinics WHERE id = ?",
            (rs, n) -> new TeamSettings(rs.getBoolean(1), rs.getBoolean(2)), clinicId);
    }

    /** Equipe que aparece no site (ativos e marcados para o site), na ordem escolhida. */
    public static List<ProfessionalView> forSite(JdbcTemplate jdbc, UUID clinicId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM professionals WHERE clinic_id = ? AND active AND show_on_site ORDER BY position, full_name",
            MAPPER, clinicId);
    }

    /** Confere que o profissional é da clínica (para vincular a um serviço); de outra clínica: 404. */
    public static void requireOwn(JdbcTemplate jdbc, UUID clinicId, UUID professionalId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM professionals WHERE id = ? AND clinic_id = ?", Integer.class,
            professionalId, clinicId);
        if (n == null || n == 0) throw new NoSuchElementException();
    }

    private ProfessionalView find(UUID clinicId, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM professionals WHERE id = ? AND clinic_id = ?", MAPPER, id, clinicId)
            .stream().findFirst().orElseThrow(NoSuchElementException::new);
    }

    private byte[] photoOf(UUID clinicId, UUID id, boolean publicOnly) {
        String extra = publicOnly
            ? " AND active AND show_on_site AND (SELECT site_show_team AND site_team_photos FROM clinics WHERE id = ?)" : "";
        Object[] args = publicOnly ? new Object[] {id, clinicId, clinicId} : new Object[] {id, clinicId};
        return jdbc.query("SELECT photo FROM professionals WHERE id = ? AND clinic_id = ? AND photo IS NOT NULL" + extra,
                (rs, n) -> rs.getBytes(1), args)
            .stream().findFirst().orElseThrow(NoSuchElementException::new);
    }

    private static ResponseEntity<byte[]> serve(byte[] data, CacheControl cache) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(cache).body(data);
    }

    private static String clean(String text) {
        return text.trim().replaceAll("\\s+", " ");
    }
}
