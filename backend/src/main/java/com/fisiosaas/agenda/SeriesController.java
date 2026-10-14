package com.fisiosaas.agenda;

import com.fisiosaas.agenda.Agenda.AppointmentView;
import com.fisiosaas.agenda.Agenda.Slot;
import com.fisiosaas.api.ConflictException;
import com.fisiosaas.identity.CurrentClinic;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Sessões e bloqueios recorrentes (ex.: seg/qua/sex às 8h por 5 semanas; almoço todo dia útil).
 * Todas as ocorrências são criadas de uma vez. Se alguma data estiver ocupada, nada é criado e a resposta (409)
 * lista os conflitos; o painel pode repetir o pedido com skipConflicts para criar só as datas livres.
 */
@RestController
@RequestMapping("/api/v1/appointments/series")
public class SeriesController {
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM");
    static final String[] WEEKDAY = {"seg", "ter", "qua", "qui", "sex", "sáb", "dom"};

    public record SeriesRequest(
        String kind,
        UUID patientId,
        UUID serviceId,
        @NotNull LocalDate startDate,
        @NotNull LocalTime startTime,
        @Min(5) @Max(720) Integer durationMinutes,
        @NotEmpty List<@NotNull @Min(1) @Max(7) Integer> weekdays,
        @Min(1) @Max(52) Integer weeks,
        @Min(1) @Max(100) Integer occurrences,
        @Size(max = 500) String notes,
        Boolean skipConflicts) {}

    public record SeriesResult(UUID seriesId, List<AppointmentView> created, List<String> skipped) {}

    private final JdbcTemplate jdbc;
    private final CurrentClinic current;
    private final Agenda agenda;

    public SeriesController(JdbcTemplate jdbc, CurrentClinic current, Agenda agenda) {
        this.jdbc = jdbc;
        this.current = current;
        this.agenda = agenda;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public SeriesResult create(Authentication auth, @Valid @RequestBody SeriesRequest body) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        boolean block = "BLOCK".equals(body.kind());
        if (!block && body.kind() != null && !"SESSION".equals(body.kind())) throw new IllegalArgumentException("Tipo inválido");

        int minutes;
        if (block) {
            if (body.patientId() != null || body.serviceId() != null) {
                throw new IllegalArgumentException("Bloqueio não tem paciente nem serviço");
            }
            if (body.durationMinutes() == null) throw new IllegalArgumentException("Informe a duração do bloqueio");
            minutes = body.durationMinutes();
        } else {
            if (body.patientId() == null || body.serviceId() == null) {
                throw new IllegalArgumentException("Escolha o paciente e o serviço");
            }
            agenda.requireOwnPatient(clinicId, body.patientId());
            int defaultMinutes = agenda.requireOwnService(clinicId, body.serviceId());
            minutes = body.durationMinutes() == null ? defaultMinutes : body.durationMinutes();
            if (minutes > 480) throw new IllegalArgumentException("Sessão de no máximo 8 horas");
        }

        Set<DayOfWeek> weekdays = body.weekdays().stream().map(DayOfWeek::of)
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(DayOfWeek.class)));
        List<LocalDate> dates = Recurrence.dates(body.startDate(), weekdays, body.weeks(), body.occurrences());

        List<Slot> free = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        for (LocalDate date : dates) {
            LocalDateTime start = date.atTime(body.startTime());
            Slot slot = new Slot(start, start.plusMinutes(minutes));
            agenda.conflictWith(clinicId, slot, null).ifPresentOrElse(
                conflict -> conflicts.add(label(date) + ": " + conflict),
                () -> free.add(slot));
        }
        if (free.isEmpty()) throw new ConflictException("Todas as datas estão ocupadas", conflicts);
        if (!conflicts.isEmpty() && !Boolean.TRUE.equals(body.skipConflicts())) {
            throw new ConflictException(conflicts.size() + " de " + dates.size() + " datas estão ocupadas", conflicts);
        }

        UUID seriesId = UUID.randomUUID();
        String notes = body.notes() == null || body.notes().isBlank() ? null : body.notes().trim();
        jdbc.update("""
            INSERT INTO appointment_series(id, clinic_id, kind, patient_id, service_id, weekdays, start_date, start_time,
                                           duration_minutes, weeks, occurrences, notes)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            seriesId, clinicId, block ? "BLOCK" : "SESSION", body.patientId(), body.serviceId(),
            weekdays.stream().map(d -> String.valueOf(d.getValue())).collect(Collectors.joining(",")),
            body.startDate(), body.startTime(), minutes, body.weeks(), body.occurrences(), notes);

        ZoneId zone = agenda.zone(clinicId);
        List<UUID> ids = new ArrayList<>();
        agenda.write(() -> {
            for (Slot slot : free) {
                UUID id = UUID.randomUUID();
                jdbc.update("""
                    INSERT INTO appointments(id, clinic_id, kind, patient_id, service_id, starts_at, ends_at, notes, series_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    id, clinicId, block ? "BLOCK" : "SESSION", body.patientId(), body.serviceId(),
                    agenda.toInstant(slot.startsAt(), zone), agenda.toInstant(slot.endsAt(), zone), notes, seriesId);
                ids.add(id);
            }
        });
        List<AppointmentView> created = ids.stream().map(id -> agenda.find(clinicId, id)).toList();
        return new SeriesResult(seriesId, created, conflicts);
    }

    /**
     * Cancela de uma vez as ocorrências ainda em aberto (agendadas/confirmadas) a partir de uma data
     * (padrão: hoje). Realizadas, faltas e canceladas ficam como estão.
     */
    @DeleteMapping("/{id}")
    @Transactional
    public Map<String, Integer> cancelUpcoming(Authentication auth, @PathVariable UUID id,
                                               @RequestParam(required = false) LocalDate from) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM appointment_series WHERE id = ? AND clinic_id = ?",
            Integer.class, id, clinicId);
        if (exists == null || exists == 0) throw new NoSuchElementException();
        ZoneId zone = agenda.zone(clinicId);
        LocalDate start = from == null ? LocalDate.now(zone) : from;
        int canceled = jdbc.update("""
            UPDATE appointments SET status = 'CANCELED', updated_at = now()
             WHERE clinic_id = ? AND series_id = ? AND starts_at >= ? AND status IN ('SCHEDULED', 'CONFIRMED')""",
            clinicId, id, agenda.toInstant(start.atStartOfDay(), zone));
        return Map.of("canceled", canceled);
    }

    private static String label(LocalDate date) {
        return WEEKDAY[date.getDayOfWeek().getValue() - 1] + ", " + date.format(DAY);
    }
}
