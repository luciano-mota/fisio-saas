package com.fisiosaas.patient;

import com.fisiosaas.agenda.Agenda;
import com.fisiosaas.identity.CurrentClinic;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Aniversariantes: pacientes ativos e desativados (a clínica pode oferecer um presente para quem sumiu) e o modelo
 * de mensagem de parabéns, enviado pelo WhatsApp (link wa.me) a partir do painel.
 */
@RestController
@RequestMapping("/api/v1/patients")
public class BirthdayController {
    /** Variáveis: {nome} (primeiro nome), {idade} e {clinica}. */
    public static final String DEFAULT_MESSAGE =
        "Feliz aniversário, {nome}! 🎂🎉 A equipe da {clinica} deseja um novo ano cheio de saúde, disposição e "
            + "movimento. Conte sempre com a gente! 💚";
    static final int MAX_DAYS = 366;

    public record Birthday(UUID id, String fullName, String whatsapp, LocalDate birthDate, boolean active,
                           LocalDate date, int age) {}

    public record BirthdayList(LocalDate today, LocalDate from, LocalDate to, String template, boolean customTemplate,
                               List<Birthday> items) {}

    public record MessageRequest(@Size(max = 1000) String message) {}

    public record MessageView(String template, boolean customTemplate) {}

    private record Row(UUID id, String fullName, String whatsapp, LocalDate birthDate, boolean active) {}

    private final JdbcTemplate jdbc;
    private final CurrentClinic current;
    private final Agenda agenda;

    public BirthdayController(JdbcTemplate jdbc, CurrentClinic current, Agenda agenda) {
        this.jdbc = jdbc;
        this.current = current;
        this.agenda = agenda;
    }

    /** Quem faz aniversário entre {@code from} e {@code to} (padrão: hoje, no fuso da clínica). */
    @GetMapping("/birthdays")
    public BirthdayList list(Authentication auth,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        UUID clinicId = current.require(auth).clinicId();
        LocalDate today = LocalDate.now(agenda.zone(clinicId));
        LocalDate start = from == null ? today : from;
        LocalDate end = to == null ? start : to;
        if (end.isBefore(start)) throw new IllegalArgumentException("A data final deve ser depois da inicial");
        if (ChronoUnit.DAYS.between(start, end) > MAX_DAYS) throw new IllegalArgumentException("Escolha um período de até um ano");

        List<Row> rows = jdbc.query("""
                SELECT id, full_name, whatsapp, birth_date, active FROM patients
                 WHERE clinic_id = ? AND birth_date IS NOT NULL""",
            (rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("full_name"), rs.getString("whatsapp"),
                rs.getObject("birth_date", LocalDate.class), rs.getBoolean("active")), clinicId);
        List<Birthday> items = new ArrayList<>();
        for (Row row : rows) {
            for (int year = start.getYear(); year <= end.getYear(); year++) {
                if (year <= row.birthDate().getYear()) continue;
                LocalDate date = occurrence(row.birthDate(), year);
                if (!date.isBefore(start) && !date.isAfter(end)) {
                    items.add(new Birthday(row.id(), row.fullName(), row.whatsapp(), row.birthDate(), row.active(), date,
                        year - row.birthDate().getYear()));
                }
            }
        }
        items.sort(Comparator.comparing(Birthday::date).thenComparing(b -> PatientController.searchKey(b.fullName())));
        MessageView message = message(clinicId);
        return new BirthdayList(today, start, end, message.template(), message.customTemplate(), items);
    }

    /** Muda o modelo da mensagem de parabéns; vazio volta ao modelo padrão. */
    @PutMapping("/birthday-message")
    public MessageView saveMessage(Authentication auth, @Valid @RequestBody MessageRequest body) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        String text = body.message() == null || body.message().isBlank() ? null : body.message().strip();
        jdbc.update("UPDATE clinics SET birthday_message = ? WHERE id = ?", text, clinicId);
        return message(clinicId);
    }

    private MessageView message(UUID clinicId) {
        String custom = jdbc.queryForObject("SELECT birthday_message FROM clinics WHERE id = ?", String.class, clinicId);
        return new MessageView(custom == null ? DEFAULT_MESSAGE : custom, custom != null);
    }

    /** Aniversário no ano informado; quem nasceu em 29/02 comemora em 28/02 nos anos que não são bissextos. */
    static LocalDate occurrence(LocalDate birthDate, int year) {
        MonthDay day = MonthDay.from(birthDate);
        return day.isValidYear(year) ? day.atYear(year) : LocalDate.of(year, 2, 28);
    }
}
