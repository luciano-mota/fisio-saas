-- Avisos da plataforma (equipe PhysioEvo) para as clínicas: para todas ou para uma só. Nada é apagado: desativa.
CREATE TABLE platform_notices (
    id         UUID PRIMARY KEY,
    title      VARCHAR(120)  NOT NULL,
    body       VARCHAR(2000) NOT NULL,
    level      VARCHAR(10)   NOT NULL CHECK (level IN ('INFO', 'IMPORTANT', 'URGENT')),
    display    VARCHAR(10)   NOT NULL CHECK (display IN ('BANNER', 'MODAL')),
    clinic_id  UUID REFERENCES clinics(id),          -- NULL = todas as clínicas
    starts_on  DATE          NOT NULL,
    ends_on    DATE,                                  -- NULL = sem data para sair
    active     BOOLEAN       NOT NULL DEFAULT TRUE,
    created_by VARCHAR(255)  NOT NULL,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CHECK (ends_on IS NULL OR ends_on >= starts_on)
);
CREATE INDEX platform_notices_clinic ON platform_notices (clinic_id);

-- Quem já leu (ou dispensou) cada aviso.
CREATE TABLE platform_notice_reads (
    notice_id UUID NOT NULL REFERENCES platform_notices(id),
    user_id   UUID NOT NULL REFERENCES users(id),
    read_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (notice_id, user_id)
);
