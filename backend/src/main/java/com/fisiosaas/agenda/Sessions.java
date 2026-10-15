package com.fisiosaas.agenda;

import com.fisiosaas.agenda.Agenda.Slot;
import com.fisiosaas.patientpackage.PackageLedger;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Cria sessão avulsa na agenda (usado pelo painel e pelo aceite de solicitações do site). Chamar dentro de transação. */
@Component
public class Sessions {
    private final JdbcTemplate jdbc;
    private final Agenda agenda;
    private final PackageLedger ledger;

    public Sessions(JdbcTemplate jdbc, Agenda agenda, PackageLedger ledger) {
        this.jdbc = jdbc;
        this.agenda = agenda;
        this.ledger = ledger;
    }

    /** Confere paciente e serviço da clínica e o horário livre; liga a um pacote do paciente com vaga, se houver. */
    public UUID create(UUID clinicId, UUID patientId, UUID serviceId, LocalDateTime startsAt, Integer durationMinutes,
                       String notes) {
        agenda.requireOwnPatient(clinicId, patientId);
        int defaultMinutes = agenda.requireOwnService(clinicId, serviceId);
        int minutes = durationMinutes == null ? defaultMinutes : durationMinutes;
        Slot slot = new Slot(startsAt, startsAt.plusMinutes(minutes));
        agenda.requireFree(clinicId, slot, null);

        ZoneId zone = agenda.zone(clinicId);
        UUID id = UUID.randomUUID();
        agenda.write(() -> jdbc.update("""
            INSERT INTO appointments(id, clinic_id, kind, patient_id, service_id, starts_at, ends_at, notes)
            VALUES (?, ?, 'SESSION', ?, ?, ?, ?, ?)""",
            id, clinicId, patientId, serviceId, agenda.toInstant(slot.startsAt(), zone),
            agenda.toInstant(slot.endsAt(), zone), notes == null || notes.isBlank() ? null : notes.trim()));
        ledger.linkNew(clinicId, id);
        return id;
    }
}
