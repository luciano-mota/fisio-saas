-- Situação da assinatura de cada clínica. Nada é apagado: suspensa ou vencida, a clínica só consulta;
-- bloqueada, ninguém entra e o site sai do ar. Os dados continuam guardados em todos os casos.
--   TRIAL     período de testes (access_until = fim do teste)
--   ACTIVE    assinante (access_until opcional: pago até)
--   FREE      acesso gratuito liberado pela plataforma (access_until opcional: NULL = sem prazo)
--   SUSPENDED suspensa: só consulta; o site fica no ar como vitrine, sem agendamento
--   BLOCKED   bloqueada: sem login e sem site
-- Clínicas que já existem continuam ativas, sem prazo.
ALTER TABLE clinics
    ADD COLUMN status            VARCHAR(12) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('TRIAL', 'ACTIVE', 'FREE', 'SUSPENDED', 'BLOCKED')),
    ADD COLUMN access_until      DATE,
    ADD COLUMN status_reason     VARCHAR(300),
    ADD COLUMN status_changed_at TIMESTAMPTZ;

-- Administradores da plataforma (equipe PhysioEvo): não pertencem a nenhuma clínica e não veem dados de pacientes.
CREATE TABLE platform_admins (
    id            UUID PRIMARY KEY,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX platform_admins_email ON platform_admins (lower(email));

-- Histórico das ações da plataforma sobre cada clínica (quem, quando, o quê e por quê).
CREATE TABLE clinic_access_log (
    id            UUID PRIMARY KEY,
    clinic_id     UUID NOT NULL REFERENCES clinics(id),
    admin_email   VARCHAR(255) NOT NULL,
    action        VARCHAR(12) NOT NULL,
    status_before VARCHAR(12) NOT NULL,
    status_after  VARCHAR(12) NOT NULL,
    access_until  DATE,
    reason        VARCHAR(300) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX clinic_access_log_clinic ON clinic_access_log (clinic_id, created_at DESC);
