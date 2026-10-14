-- Conteúdo de confiança do site da clínica (passo 1 do site profissional).
ALTER TABLE clinics ADD COLUMN site_education    VARCHAR(600);  -- formação e especializações
ALTER TABLE clinics ADD COLUMN site_hours        VARCHAR(300);  -- texto livre até existir o horário de atendimento
ALTER TABLE clinics ADD COLUMN site_service_area VARCHAR(300);  -- bairros/cidades do atendimento domiciliar
ALTER TABLE clinics ADD COLUMN site_show_map     BOOLEAN NOT NULL DEFAULT TRUE;
-- Depoimentos ficam desligados até a clínica decidir publicar (conferir a regra de publicidade do COFFITO).
ALTER TABLE clinics ADD COLUMN site_show_testimonials BOOLEAN NOT NULL DEFAULT FALSE;

-- Fotos do site: uma capa e até 6 na galeria (limite conferido na API). Guardadas no banco, como a logo.
-- Exceção à regra "nada é apagado": foto não é histórico e pode mostrar pessoas (LGPD), então remover apaga.
-- Só JPEG: o painel recomprime no navegador e o servidor tira metadados (EXIF/GPS) antes de salvar.
CREATE TABLE clinic_photos (
    id            UUID PRIMARY KEY,
    clinic_id     UUID NOT NULL REFERENCES clinics(id),
    kind          VARCHAR(10) NOT NULL CHECK (kind IN ('COVER', 'GALLERY')),
    content_type  VARCHAR(20) NOT NULL CHECK (content_type = 'image/jpeg'),
    data          BYTEA NOT NULL CHECK (octet_length(data) <= 819200),
    width         INTEGER NOT NULL CHECK (width BETWEEN 320 AND 2400),
    height        INTEGER NOT NULL CHECK (height BETWEEN 320 AND 2400),
    caption       VARCHAR(140),
    position      INTEGER NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX clinic_photos_one_cover ON clinic_photos (clinic_id) WHERE kind = 'COVER';
CREATE INDEX clinic_photos_clinic_idx ON clinic_photos (clinic_id, kind, position);

-- Depoimentos: só com autorização do paciente, confirmada pela clínica no cadastro.
CREATE TABLE clinic_testimonials (
    id                    UUID PRIMARY KEY,
    clinic_id             UUID NOT NULL REFERENCES clinics(id),
    author_name           VARCHAR(60)  NOT NULL,
    text                  VARCHAR(500) NOT NULL,
    position              INTEGER NOT NULL DEFAULT 0,
    active                BOOLEAN NOT NULL DEFAULT TRUE,
    consent_confirmed_at  TIMESTAMPTZ NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX clinic_testimonials_clinic_idx ON clinic_testimonials (clinic_id, position);

CREATE TABLE clinic_faqs (
    id          UUID PRIMARY KEY,
    clinic_id   UUID NOT NULL REFERENCES clinics(id),
    question    VARCHAR(200) NOT NULL,
    answer      VARCHAR(800) NOT NULL,
    position    INTEGER NOT NULL DEFAULT 0,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX clinic_faqs_clinic_idx ON clinic_faqs (clinic_id, position);
