-- Equipe multiprofissional: fisioterapeutas, nutricionistas, psicólogos etc. Foto opcional (apagar a foto apaga de
-- verdade: imagem de pessoa, LGPD); o profissional em si é desativado, nunca apagado.
CREATE TABLE professionals (
    id           UUID PRIMARY KEY,
    clinic_id    UUID NOT NULL REFERENCES clinics(id),
    full_name    VARCHAR(120) NOT NULL,
    profession   VARCHAR(60)  NOT NULL,
    council      VARCHAR(40),                       -- ex.: "CRN-5 12345", "CRP 06/12345"
    bio          VARCHAR(600),
    show_on_site BOOLEAN NOT NULL DEFAULT TRUE,
    position     INTEGER NOT NULL DEFAULT 0,
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    photo        BYTEA CHECK (photo IS NULL OR octet_length(photo) <= 819200),
    photo_width  INTEGER,
    photo_height INTEGER,
    photo_version BIGINT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT professionals_clinic_id_unique UNIQUE (clinic_id, id)
);
CREATE INDEX professionals_clinic ON professionals (clinic_id, active, position);

-- Serviço pode indicar quem atende (opcional), sempre da mesma clínica.
ALTER TABLE services ADD COLUMN professional_id UUID;
ALTER TABLE services ADD CONSTRAINT services_professional_fk
    FOREIGN KEY (clinic_id, professional_id) REFERENCES professionals (clinic_id, id);

-- Seção "Nossa equipe" no site e as fotos dela (slides) são opcionais.
ALTER TABLE clinics
    ADD COLUMN site_show_team   BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN site_team_photos BOOLEAN NOT NULL DEFAULT TRUE;
