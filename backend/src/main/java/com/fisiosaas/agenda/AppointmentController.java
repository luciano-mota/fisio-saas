package com.fisiosaas.agenda;

import com.fisiosaas.agenda.Agenda.AppointmentView;
import com.fisiosaas.agenda.Agenda.Slot;
import com.fisiosaas.agenda.Agenda.Status;
import com.fisiosaas.identity.CurrentClinic;
import com.fisiosaas.patientpackage.PackageLedger;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Agenda da clínica: sessões avulsas, mudança de status, remarcação e bloqueio de horários.
 * Datas e horas são locais da clínica (ex.: "2026-10-15T08:00").
 */
@RestController
@RequestMapping("/api/v1")
public class AppointmentController {
    static final int MAX_RANGE_DAYS = 62;

    public record SessionRequest(
        @NotNull UUID patientId,
        @NotNull UUID serviceId,
        @NotNull LocalDateTime startsAt,
        @Min(5) @Max(480) Integer durationMinutes,
        @Size(max = 500) String notes) {}

    public record BlockRequest(
        @NotNull LocalDateTime startsAt,
        @NotNull LocalDateTime endsAt,
        @Size(max = 500) String notes) {}

    /** Remarcação: novo início e, opcionalmente, nova duração. Observação pode ser trocada junto. */
    public record RescheduleRequest(
        @NotNull LocalDateTime startsAt,
        @Min(5) @Max(720) Integer durationMinutes,
        @Size(max = 500) String notes) {}

    public record StatusRequest(@NotNull Status status) {}

    /** Pacote vendido que a sessão usa; null deixa a sessão avulsa. */
    public record PackageRequest(UUID patientPackageId) {}

    private final JdbcTemplate jdbc;
    private final CurrentClinic current;
    private final Agenda agenda;
    private final PackageLedger ledger;

    public AppointmentController(JdbcTemplate jdbc, CurrentClinic current, Agenda agenda, PackageLedger ledger) {
        this.jdbc = jdbc;
        this.current = current;
        this.agenda = agenda;
        this.ledger = ledger;
    }

    /** Sessões e bloqueios entre as datas (inclusive), em ordem de horário. */
    @GetMapping("/appointments")
    public List<AppointmentView> list(Authentication auth, @RequestParam LocalDate from, @RequestParam LocalDate to,
                                      @RequestParam(defaultValue = "false") boolean includeCanceled) {
        UUID clinicId = current.require(auth).clinicId();
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("Período inválido (máximo " + MAX_RANGE_DAYS + " dias)");
        }
        return agenda.between(clinicId, from.atStartOfDay(), to.plusDays(1).atStartOfDay(), includeCanceled);
    }

    @GetMapping("/appointments/{id}")
    public AppointmentView get(Authentication auth, @PathVariable UUID id) {
        return agenda.find(current.require(auth).clinicId(), id);
    }

    @PostMapping("/appointments")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public AppointmentView create(Authentication auth, @Valid @RequestBody SessionRequest body) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        agenda.requireOwnPatient(clinicId, body.patientId());
        int defaultMinutes = agenda.requireOwnService(clinicId, body.serviceId());
        int minutes = body.durationMinutes() == null ? defaultMinutes : body.durationMinutes();
        Slot slot = new Slot(body.startsAt(), body.startsAt().plusMinutes(minutes));
        agenda.requireFree(clinicId, slot, null);

        ZoneId zone = agenda.zone(clinicId);
        UUID id = UUID.randomUUID();
        agenda.write(() -> jdbc.update("""
            INSERT INTO appointments(id, clinic_id, kind, patient_id, service_id, starts_at, ends_at, notes)
            VALUES (?, ?, 'SESSION', ?, ?, ?, ?, ?)""",
            id, clinicId, body.patientId(), body.serviceId(), agenda.toInstant(slot.startsAt(), zone),
            agenda.toInstant(slot.endsAt(), zone), blankToNull(body.notes())));
        ledger.linkNew(clinicId, id);
        return agenda.find(clinicId, id);
    }

    @PostMapping("/blocks")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public AppointmentView block(Authentication auth, @Valid @RequestBody BlockRequest body) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        Slot slot = new Slot(body.startsAt(), body.endsAt());
        agenda.requireFree(clinicId, slot, null);

        ZoneId zone = agenda.zone(clinicId);
        UUID id = UUID.randomUUID();
        agenda.write(() -> jdbc.update("""
            INSERT INTO appointments(id, clinic_id, kind, starts_at, ends_at, notes) VALUES (?, ?, 'BLOCK', ?, ?, ?)""",
            id, clinicId, agenda.toInstant(slot.startsAt(), zone), agenda.toInstant(slot.endsAt(), zone),
            blankToNull(body.notes())));
        return agenda.find(clinicId, id);
    }

    /** Remarca (sessão agendada/confirmada ou bloqueio). Sessão remarcada volta para "agendada", à espera de nova confirmação. */
    @PutMapping("/appointments/{id}")
    @Transactional
    public AppointmentView reschedule(Authentication auth, @PathVariable UUID id,
                                      @Valid @RequestBody RescheduleRequest body) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        AppointmentView old = agenda.find(clinicId, id);
        if (!old.status().equals("SCHEDULED") && !old.status().equals("CONFIRMED")) {
            throw new IllegalArgumentException("Só dá para remarcar o que está agendado ou confirmado");
        }
        long minutes = body.durationMinutes() != null ? body.durationMinutes()
            : ChronoUnit.MINUTES.between(old.startsAt(), old.endsAt());
        Slot slot = new Slot(body.startsAt(), body.startsAt().plusMinutes(minutes));
        boolean moved = !slot.startsAt().equals(old.startsAt()) || !slot.endsAt().equals(old.endsAt());
        if (moved) agenda.requireFree(clinicId, slot, id);

        ZoneId zone = agenda.zone(clinicId);
        OffsetDateTime newStart = agenda.toInstant(slot.startsAt(), zone);
        boolean timeChanged = !slot.startsAt().equals(old.startsAt());
        agenda.write(() -> jdbc.update("""
            UPDATE appointments
               SET starts_at = ?, ends_at = ?, notes = ?,
                   original_starts_at = CASE WHEN ? THEN coalesce(original_starts_at, starts_at) ELSE original_starts_at END,
                   reschedule_count = reschedule_count + CASE WHEN ? THEN 1 ELSE 0 END,
                   status = CASE WHEN ? AND kind = 'SESSION' THEN 'SCHEDULED' ELSE status END,
                   updated_at = now()
             WHERE id = ? AND clinic_id = ?""",
            newStart, agenda.toInstant(slot.endsAt(), zone), blankToNull(body.notes()),
            timeChanged, timeChanged, timeChanged, id, clinicId));
        return agenda.find(clinicId, id);
    }

    /**
     * Muda o status da sessão. Realizada e falta só depois do horário de início; voltar de cancelada
     * para ativa exige o horário livre.
     */
    @PostMapping("/appointments/{id}/status")
    @Transactional
    public AppointmentView changeStatus(Authentication auth, @PathVariable UUID id,
                                        @Valid @RequestBody StatusRequest body) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        AppointmentView old = agenda.find(clinicId, id);
        if (!"SESSION".equals(old.kind())) {
            throw new IllegalArgumentException("Bloqueio de horário não tem status; para liberar, remova o bloqueio");
        }
        Status target = body.status();
        if ((target == Status.DONE || target == Status.NO_SHOW)
                && agenda.toInstant(old.startsAt(), agenda.zone(clinicId)).isAfter(OffsetDateTime.now())) {
            throw new IllegalArgumentException("Só dá para marcar realizada ou falta depois do horário da sessão");
        }
        if (old.status().equals("CANCELED") && target != Status.CANCELED) {
            agenda.requireFree(clinicId, new Slot(old.startsAt(), old.endsAt()), id);
        }
        agenda.write(() -> jdbc.update(
            "UPDATE appointments SET status = ?, updated_at = now() WHERE id = ? AND clinic_id = ?",
            target.name(), id, clinicId));
        ledger.applyStatus(clinicId, id);
        return agenda.find(clinicId, id);
    }

    /** Troca o pacote usado pela sessão (ou deixa avulsa com patientPackageId null). */
    @PutMapping("/appointments/{id}/package")
    @Transactional
    public AppointmentView changePackage(Authentication auth, @PathVariable UUID id, @RequestBody PackageRequest body) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        AppointmentView old = agenda.find(clinicId, id);
        if (!"SESSION".equals(old.kind())) throw new IllegalArgumentException("Bloqueio de horário não usa pacote");
        ledger.assign(clinicId, id, body.patientPackageId());
        return agenda.find(clinicId, id);
    }

    /** Não apaga: cancela a sessão ou remove o bloqueio (fica no histórico como cancelado). */
    @DeleteMapping("/appointments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void cancel(Authentication auth, @PathVariable UUID id) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        AppointmentView old = agenda.find(clinicId, id);
        jdbc.update("UPDATE appointments SET status = 'CANCELED', updated_at = now() WHERE id = ? AND clinic_id = ?",
            id, clinicId);
        if ("SESSION".equals(old.kind())) ledger.applyStatus(clinicId, id);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
