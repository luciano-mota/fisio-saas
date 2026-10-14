-- Regra de repetição (ex.: seg/qua/sex às 8h por 5 semanas). As ocorrências são criadas de uma vez em
-- appointments; a série guarda a regra para consulta e para cancelar as próximas de uma vez.
CREATE TABLE appointment_series (
    id               UUID PRIMARY KEY,
    clinic_id        UUID NOT NULL REFERENCES clinics(id),
    kind             VARCHAR(10) NOT NULL CHECK (kind IN ('SESSION', 'BLOCK')),
    patient_id       UUID,
    service_id       UUID,
    weekdays         VARCHAR(20) NOT NULL CHECK (weekdays ~ '^[1-7](,[1-7]){0,6}$'),  -- ISO: 1 = segunda … 7 = domingo
    start_date       DATE NOT NULL,
    start_time       TIME NOT NULL,
    duration_minutes INTEGER NOT NULL CHECK (duration_minutes BETWEEN 5 AND 720),
    weeks            INTEGER CHECK (weeks BETWEEN 1 AND 52),
    occurrences      INTEGER CHECK (occurrences BETWEEN 1 AND 100),
    notes            VARCHAR(500),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT appointment_series_clinic_id_unique UNIQUE (clinic_id, id),
    CONSTRAINT appointment_series_patient_fk FOREIGN KEY (clinic_id, patient_id) REFERENCES patients (clinic_id, id),
    CONSTRAINT appointment_series_service_fk FOREIGN KEY (clinic_id, service_id) REFERENCES services (clinic_id, id),
    CONSTRAINT appointment_series_limit CHECK (weeks IS NOT NULL OR occurrences IS NOT NULL)
);

ALTER TABLE appointments ADD COLUMN series_id UUID;
ALTER TABLE appointments ADD CONSTRAINT appointments_series_fk
    FOREIGN KEY (clinic_id, series_id) REFERENCES appointment_series (clinic_id, id);
CREATE INDEX appointments_series_idx ON appointments (clinic_id, series_id) WHERE series_id IS NOT NULL;
