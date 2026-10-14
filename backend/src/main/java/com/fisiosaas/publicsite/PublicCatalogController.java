package com.fisiosaas.publicsite;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Vitrine pública da clínica (sem login). Só expõe o que a clínica decidiu mostrar:
 * página precisa estar ativada, itens inativos não aparecem e preço só aparece com show_price.
 */
@RestController
@RequestMapping("/api/v1/public/clinics")
public class PublicCatalogController {

    public record Theme(String primary, String secondary, String accent) {}

    public record PublicPackage(UUID id, String name, int sessionCount, BigDecimal price, Integer validityDays) {}

    public record PublicService(UUID id, String name, String description, int durationMinutes,
                                BigDecimal price, boolean onlineBooking, List<PublicPackage> packages) {}

    public record PublicClinic(String slug, String displayName, String professionalName, String crefito,
                               String whatsapp, String address, String about, Theme theme,
                               Long logoVersion, Site site, List<PublicService> services) {}

    /** Aparência escolhida pela clínica ("auto" = o site decide pelo tipo de serviço). */
    public record Site(String style, String fonts, String headline) {}

    private final JdbcTemplate jdbc;

    public PublicCatalogController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/{slug}")
    public ResponseEntity<PublicClinic> clinic(@PathVariable String slug) {
        record Row(UUID id, PublicClinic base) {}
        Row clinic = jdbc.query("""
                SELECT c.*, (SELECT (extract(epoch FROM l.updated_at) * 1000)::bigint FROM clinic_logos l
                              WHERE l.clinic_id = c.id) AS logo_version
                  FROM clinics c WHERE c.slug = ? AND c.public_page_enabled""",
            (rs, n) -> new Row(rs.getObject("id", UUID.class), new PublicClinic(
                rs.getString("slug"), rs.getString("display_name"), rs.getString("professional_name"),
                rs.getString("crefito"), rs.getString("whatsapp"), rs.getString("address"), rs.getString("about"),
                new Theme(rs.getString("color_primary"), rs.getString("color_secondary"),
                    rs.getString("color_accent")), (Long) rs.getObject("logo_version"),
                new Site(rs.getString("site_style"), rs.getString("site_fonts"), rs.getString("site_headline")),
                List.of())),
            slug.toLowerCase(Locale.ROOT)).stream().findFirst().orElseThrow(NoSuchElementException::new);

        List<PublicService> services = jdbc.query("""
                SELECT * FROM services WHERE clinic_id = ? AND active ORDER BY display_order, name""",
            (rs, n) -> {
                UUID serviceId = rs.getObject("id", UUID.class);
                return new PublicService(serviceId, rs.getString("name"), rs.getString("description"),
                    rs.getInt("duration_minutes"),
                    rs.getBoolean("show_price") ? rs.getBigDecimal("price") : null,
                    rs.getBoolean("online_booking_enabled"),
                    packages(clinic.id(), serviceId));
            }, clinic.id());

        PublicClinic base = clinic.base();
        PublicClinic body = new PublicClinic(base.slug(), base.displayName(), base.professionalName(),
            base.crefito(), base.whatsapp(), base.address(), base.about(), base.theme(), base.logoVersion(), base.site(),
            services);
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(body);
    }

    private List<PublicPackage> packages(UUID clinicId, UUID serviceId) {
        return jdbc.query("""
                SELECT * FROM session_packages
                 WHERE clinic_id = ? AND service_id = ? AND active ORDER BY session_count""",
            (rs, n) -> new PublicPackage(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getInt("session_count"), rs.getBoolean("show_price") ? rs.getBigDecimal("price") : null,
                (Integer) rs.getObject("validity_days")),
            clinicId, serviceId);
    }
}
