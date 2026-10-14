-- Agenda: sessões e bloqueios de horário na mesma tabela, para o banco impedir sobreposição entre eles.
-- btree_gist é extensão "trusted" do PostgreSQL 13+: o dono do banco pode criá-la.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- Datas e horas digitadas no painel são do fuso da clínica (Maranhão = America/Fortaleza, sem horário de verão).
ALTER TABLE clinics ADD COLUMN timezone VARCHAR(40) NOT NULL DEFAULT 'America/Fortaleza';

-- Permite chaves estrangeiras que exigem a MESMA clínica (paciente/serviço de outra clínica é recusado pelo banco).
ALTER TABLE patients ADD CONSTRAINT patients_clinic_id_unique UNIQUE (clinic_id, id);
ALTER TABLE services ADD CONSTRAINT services_clinic_id_unique UNIQUE (clinic_id, id);

CREATE TABLE appointments (
    id                 UUID PRIMARY KEY,
    clinic_id          UUID NOT NULL REFERENCES clinics(id),
    kind               VARCHAR(10) NOT NULL CHECK (kind IN ('SESSION', 'BLOCK')),
    patient_id         UUID,
    service_id         UUID,
    starts_at          TIMESTAMPTZ NOT NULL,
    ends_at            TIMESTAMPTZ NOT NULL,
    status             VARCHAR(12) NOT NULL DEFAULT 'SCHEDULED'
                       CHECK (status IN ('SCHEDULED', 'CONFIRMED', 'DONE', 'NO_SHOW', 'CANCELED')),
    notes              VARCHAR(500),           -- observação administrativa; no bloqueio, o motivo
    original_starts_at TIMESTAMPTZ,            -- horário antes da primeira remarcação
    reschedule_count   INTEGER NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT appointments_patient_fk FOREIGN KEY (clinic_id, patient_id) REFERENCES patients (clinic_id, id),
    CONSTRAINT appointments_service_fk FOREIGN KEY (clinic_id, service_id) REFERENCES services (clinic_id, id),
    CONSTRAINT appointments_duration CHECK (ends_at > starts_at AND ends_at - starts_at <= INTERVAL '12 hours'),
    CONSTRAINT appointments_kind_fields CHECK (
        (kind = 'SESSION' AND patient_id IS NOT NULL AND service_id IS NOT NULL)
        OR (kind = 'BLOCK' AND patient_id IS NULL AND service_id IS NULL AND status IN ('SCHEDULED', 'CANCELED'))),
    -- Sem conflito de horário na mesma clínica. Cancelados liberam o horário.
    CONSTRAINT appointments_no_overlap EXCLUDE USING gist (
        clinic_id WITH =, tstzrange(starts_at, ends_at) WITH &&) WHERE (status <> 'CANCELED')
);
CREATE INDEX appointments_clinic_time_idx ON appointments (clinic_id, starts_at);
CREATE INDEX appointments_patient_idx ON appointments (clinic_id, patient_id, starts_at);
