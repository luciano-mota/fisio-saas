-- Regra em aberto, configurável por clínica: falta sem aviso consome sessão do pacote? Padrão: não consome.
ALTER TABLE clinics ADD COLUMN no_show_consumes_session BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE session_packages ADD CONSTRAINT session_packages_clinic_id_unique UNIQUE (clinic_id, id);

-- Pacote vendido a um paciente. Nome, nº de sessões e preço são COPIADOS do catálogo no momento da venda:
-- mudar o catálogo depois não altera o que já foi vendido.
CREATE TABLE patient_packages (
    id             UUID PRIMARY KEY,
    clinic_id      UUID NOT NULL REFERENCES clinics(id),
    patient_id     UUID NOT NULL,
    package_id     UUID NOT NULL,
    service_id     UUID NOT NULL,
    name           VARCHAR(120) NOT NULL,
    session_count  INTEGER NOT NULL CHECK (session_count BETWEEN 1 AND 200),
    price          NUMERIC(10,2) CHECK (price IS NULL OR price >= 0),
    purchased_on   DATE NOT NULL,
    expires_on     DATE CHECK (expires_on IS NULL OR expires_on >= purchased_on),
    notes          VARCHAR(500),
    active         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT patient_packages_clinic_id_unique UNIQUE (clinic_id, id),
    -- Permite à sessão exigir, pelo banco, pacote do MESMO paciente e do MESMO serviço.
    CONSTRAINT patient_packages_owner_unique UNIQUE (clinic_id, patient_id, service_id, id),
    CONSTRAINT patient_packages_patient_fk FOREIGN KEY (clinic_id, patient_id) REFERENCES patients (clinic_id, id),
    CONSTRAINT patient_packages_service_fk FOREIGN KEY (clinic_id, service_id) REFERENCES services (clinic_id, id),
    CONSTRAINT patient_packages_package_fk FOREIGN KEY (clinic_id, package_id) REFERENCES session_packages (clinic_id, id)
);
CREATE INDEX patient_packages_patient_idx ON patient_packages (clinic_id, patient_id, service_id, active);

-- Sessão ligada a um pacote. counts_against_package é gravado na mudança de status (realizada sempre; falta conforme a
-- regra da clínica NAQUELE momento), para que mudar a regra depois não reescreva o histórico.
ALTER TABLE appointments ADD COLUMN patient_package_id UUID;
ALTER TABLE appointments ADD COLUMN counts_against_package BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE appointments ADD CONSTRAINT appointments_patient_package_fk
    FOREIGN KEY (clinic_id, patient_id, service_id, patient_package_id)
    REFERENCES patient_packages (clinic_id, patient_id, service_id, id);
ALTER TABLE appointments ADD CONSTRAINT appointments_counts_need_package
    CHECK (NOT counts_against_package OR patient_package_id IS NOT NULL);
CREATE INDEX appointments_patient_package_idx ON appointments (patient_package_id) WHERE patient_package_id IS NOT NULL;
