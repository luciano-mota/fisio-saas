package com.fisiosaas.publicsite;

import com.fisiosaas.clinic.FaqController;
import com.fisiosaas.clinic.PhotoController;
import com.fisiosaas.clinic.PhotoController.PhotoView;
import com.fisiosaas.clinic.TestimonialController;
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
                               Long logoVersion, Site site, List<PublicService> services, Booking booking) {}

    /** Agendamento pelo site ligado? E o horário de atendimento (weekday ISO, "HH:mm"). */
    /** {@code pix}: a clínica cadastrou chave Pix (o site oferece "Pagar agora via Pix" quando o preço é visível). */
    public record Booking(boolean enabled, List<OpeningHours> hours, boolean pix, int maxDays) {}

    public record OpeningHours(int weekday, String start, String end) {}

    /**
     * Aparência e conteúdo do site. Estilo/fontes "auto" = o site decide pelo tipo de serviço. Depoimentos só vêm
     * se a clínica ligou a seção.
     */
    public record Site(String style, String fonts, String template, String mood, String headline, String education, String hours,
                       String serviceArea, boolean showMap, Photo cover, List<Photo> gallery,
                       List<Testimonial> testimonials, List<Faq> faqs) {}

    public record Photo(UUID id, String caption, int width, int height) {}

    public record Testimonial(String authorName, String text) {}

    public record Faq(String question, String answer) {}

    private record Content(String style, String fonts, String template, String mood, String headline, String education, String hours,
                           String serviceArea, boolean showMap, boolean showTestimonials) {}

    private final JdbcTemplate jdbc;

    public PublicCatalogController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/{slug}")
    public ResponseEntity<PublicClinic> clinic(@PathVariable String slug) {
        record Row(UUID id, PublicClinic base, Content content) {}
        Row clinic = jdbc.query("""
                SELECT c.*, (SELECT (extract(epoch FROM l.updated_at) * 1000)::bigint FROM clinic_logos l
                              WHERE l.clinic_id = c.id) AS logo_version
                  FROM clinics c WHERE c.slug = ? AND c.public_page_enabled""",
            (rs, n) -> new Row(rs.getObject("id", UUID.class), new PublicClinic(
                rs.getString("slug"), rs.getString("display_name"), rs.getString("professional_name"),
                rs.getString("crefito"), rs.getString("whatsapp"), rs.getString("address"), rs.getString("about"),
                new Theme(rs.getString("color_primary"), rs.getString("color_secondary"),
                    rs.getString("color_accent")), (Long) rs.getObject("logo_version"), null, List.of(),
                    new Booking(rs.getBoolean("booking_enabled"), List.of(), rs.getString("pix_key") != null,
                        rs.getInt("booking_max_days"))),
                new Content(rs.getString("site_style"), rs.getString("site_fonts"), rs.getString("site_template"),
                    rs.getString("site_mood"), rs.getString("site_headline"),
                    rs.getString("site_education"), rs.getString("site_hours"), rs.getString("site_service_area"),
                    rs.getBoolean("site_show_map"), rs.getBoolean("site_show_testimonials"))),
            slug.toLowerCase(Locale.ROOT)).stream().findFirst().orElseThrow(NoSuchElementException::new);
        UUID clinicId = clinic.id();

        List<PublicService> services = jdbc.query("""
                SELECT * FROM services WHERE clinic_id = ? AND active ORDER BY display_order, name""",
            (rs, n) -> {
                UUID serviceId = rs.getObject("id", UUID.class);
                return new PublicService(serviceId, rs.getString("name"), rs.getString("description"),
                    rs.getInt("duration_minutes"),
                    rs.getBoolean("show_price") ? rs.getBigDecimal("price") : null,
                    rs.getBoolean("online_booking_enabled"),
                    packages(clinicId, serviceId));
            }, clinicId);

        Content content = clinic.content();
        List<PhotoView> photos = PhotoController.list(jdbc, clinicId);
        Photo cover = photos.stream().filter(p -> "COVER".equals(p.kind())).map(PublicCatalogController::photo)
            .findFirst().orElse(null);
        List<Photo> gallery = photos.stream().filter(p -> "GALLERY".equals(p.kind()))
            .map(PublicCatalogController::photo).toList();
        List<Testimonial> testimonials = content.showTestimonials()
            ? TestimonialController.list(jdbc, clinicId).stream().map(t -> new Testimonial(t.authorName(), t.text())).toList()
            : List.of();
        List<Faq> faqs = FaqController.list(jdbc, clinicId).stream().map(f -> new Faq(f.question(), f.answer())).toList();
        Site site = new Site(content.style(), content.fonts(), content.template(), content.mood(), content.headline(), content.education(), content.hours(),
            content.serviceArea(), content.showMap(), cover, gallery, testimonials, faqs);

        PublicClinic base = clinic.base();
        List<OpeningHours> hours = jdbc.query("""
                SELECT weekday, to_char(start_time, 'HH24:MI'), to_char(end_time, 'HH24:MI') FROM clinic_hours
                 WHERE clinic_id = ? ORDER BY weekday, start_time""",
            (rs, n) -> new OpeningHours(rs.getInt(1), rs.getString(2), rs.getString(3)), clinicId);
        PublicClinic body = new PublicClinic(base.slug(), base.displayName(), base.professionalName(),
            base.crefito(), base.whatsapp(), base.address(), base.about(), base.theme(), base.logoVersion(), site,
            services, new Booking(base.booking().enabled(), hours, base.booking().pix(), base.booking().maxDays()));
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(body);
    }

    private static Photo photo(PhotoView view) {
        return new Photo(view.id(), view.caption(), view.width(), view.height());
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
