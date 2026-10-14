-- Pacientes da clínica. Só dados administrativos: nada clínico até confirmar regras do COFFITO e LGPD.
CREATE TABLE patients (
    id           UUID PRIMARY KEY,
    clinic_id    UUID NOT NULL REFERENCES clinics(id),
    full_name    VARCHAR(120) NOT NULL,
    -- Nome em minúsculas e sem acento, para a busca achar "joao" em "João".
    search_name  VARCHAR(120) NOT NULL,
    cpf          CHAR(11) CHECK (cpf IS NULL OR cpf ~ '^[0-9]{11}$'),   -- opcional, só para recibo
    whatsapp     VARCHAR(13) CHECK (whatsapp IS NULL OR whatsapp ~ '^[0-9]{10,13}$'),
    birth_date   DATE,
    admin_notes  VARCHAR(1000),
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX patients_cpf_unique ON patients (clinic_id, cpf) WHERE cpf IS NOT NULL;
CREATE INDEX patients_clinic_idx ON patients (clinic_id, active, search_name);
