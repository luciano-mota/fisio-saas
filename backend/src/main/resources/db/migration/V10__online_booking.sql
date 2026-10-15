-- Horário de atendimento e agendamento online pelo site.

-- Intervalos de atendimento por dia da semana (ISO: 1 = segunda … 7 = domingo). Vários por dia (ex.: manhã e tarde).
CREATE TABLE clinic_hours (
    id          UUID PRIMARY KEY,
    clinic_id   UUID NOT NULL REFERENCES clinics(id),
    weekday     SMALLINT NOT NULL CHECK (weekday BETWEEN 1 AND 7),
    start_time  TIME NOT NULL,
    end_time    TIME NOT NULL,
    CONSTRAINT clinic_hours_order CHECK (end_time > start_time)
);
CREATE INDEX clinic_hours_clinic_idx ON clinic_hours (clinic_id, weekday, start_time);

-- Regras do agendamento online (desligado até a clínica ativar).
ALTER TABLE clinics ADD COLUMN booking_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE clinics ADD COLUMN booking_min_notice_hours INTEGER NOT NULL DEFAULT 2 CHECK (booking_min_notice_hours BETWEEN 0 AND 168);
ALTER TABLE clinics ADD COLUMN booking_max_days INTEGER NOT NULL DEFAULT 30 CHECK (booking_max_days BETWEEN 1 AND 180);
ALTER TABLE clinics ADD COLUMN booking_hold_hours INTEGER NOT NULL DEFAULT 24 CHECK (booking_hold_hours BETWEEN 1 AND 168);
ALTER TABLE clinics ADD COLUMN booking_step_minutes INTEGER NOT NULL DEFAULT 30 CHECK (booking_step_minutes IN (15, 20, 30, 60));

ALTER TABLE appointments ADD CONSTRAINT appointments_clinic_id_unique UNIQUE (clinic_id, id);

-- Solicitação feita pelo paciente no site. O CPF identifica o cadastro (evita paciente duplicado).
-- Enquanto pendente, segura o horário até hold_until; depois disso vira EXPIRED.
CREATE TABLE booking_requests (
    id              UUID PRIMARY KEY,
    clinic_id       UUID NOT NULL REFERENCES clinics(id),
    service_id      UUID NOT NULL,
    starts_at       TIMESTAMPTZ NOT NULL,
    ends_at         TIMESTAMPTZ NOT NULL,
    full_name       VARCHAR(120) NOT NULL,
    cpf             CHAR(11) NOT NULL CHECK (cpf ~ '^[0-9]{11}$'),
    whatsapp        VARCHAR(13) NOT NULL CHECK (whatsapp ~ '^[0-9]{10,13}$'),
    birth_date      DATE,
    notes           VARCHAR(500),
    status          VARCHAR(10) NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'EXPIRED', 'CANCELED')),
    hold_until      TIMESTAMPTZ NOT NULL,
    patient_id      UUID,
    appointment_id  UUID,
    decline_reason  VARCHAR(300),
    consent_at      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at      TIMESTAMPTZ,
    CONSTRAINT booking_requests_service_fk FOREIGN KEY (clinic_id, service_id) REFERENCES services (clinic_id, id),
    CONSTRAINT booking_requests_patient_fk FOREIGN KEY (clinic_id, patient_id) REFERENCES patients (clinic_id, id),
    CONSTRAINT booking_requests_appointment_fk FOREIGN KEY (clinic_id, appointment_id) REFERENCES appointments (clinic_id, id),
    CONSTRAINT booking_requests_duration CHECK (ends_at > starts_at AND ends_at - starts_at <= INTERVAL '12 hours'),
    -- Dois pedidos pendentes não seguram o mesmo horário (dois visitantes clicando juntos).
    CONSTRAINT booking_requests_no_overlap EXCLUDE USING gist (
        clinic_id WITH =, tstzrange(starts_at, ends_at) WITH &&) WHERE (status = 'PENDING')
);
CREATE INDEX booking_requests_clinic_idx ON booking_requests (clinic_id, status, starts_at);
CREATE INDEX booking_requests_cpf_idx ON booking_requests (clinic_id, cpf);
