-- Cada clínica (profissional autônomo ou consultório) é um tenant isolado.
CREATE TABLE clinics (
    id                 UUID PRIMARY KEY,
    slug               VARCHAR(63)  NOT NULL UNIQUE
                       CHECK (slug ~ '^[a-z0-9]([a-z0-9-]{1,61}[a-z0-9])?$'),
    display_name       VARCHAR(120) NOT NULL,
    professional_name  VARCHAR(120),
    crefito            VARCHAR(30),
    document           VARCHAR(18),               -- CPF ou CNPJ, só dígitos/pontuação
    whatsapp           VARCHAR(20),
    address            VARCHAR(255),
    about              VARCHAR(1000),
    -- Tema (identidade visual). Fonte única para painel, landing page e recibo.
    color_primary      CHAR(7) NOT NULL DEFAULT '#0F766E' CHECK (color_primary   ~ '^#[0-9A-Fa-f]{6}$'),
    color_secondary    CHAR(7) NOT NULL DEFAULT '#134E4A' CHECK (color_secondary ~ '^#[0-9A-Fa-f]{6}$'),
    color_accent       CHAR(7) NOT NULL DEFAULT '#F59E0B' CHECK (color_accent    ~ '^#[0-9A-Fa-f]{6}$'),
    public_page_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id             UUID PRIMARY KEY,
    clinic_id      UUID NOT NULL REFERENCES clinics(id),
    email          VARCHAR(255) NOT NULL,
    password_hash  VARCHAR(100) NOT NULL,
    full_name      VARCHAR(120),
    role           VARCHAR(20)  NOT NULL DEFAULT 'OWNER' CHECK (role IN ('OWNER', 'STAFF')),
    active         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX users_email_unique ON users (lower(email));
CREATE INDEX users_clinic_idx ON users (clinic_id);

-- Serviços oferecidos pela clínica. Preço é opcional e a exibição no site é escolha da clínica.
CREATE TABLE services (
    id                     UUID PRIMARY KEY,
    clinic_id              UUID NOT NULL REFERENCES clinics(id),
    name                   VARCHAR(120) NOT NULL,
    description            VARCHAR(600),
    duration_minutes       INTEGER NOT NULL DEFAULT 50 CHECK (duration_minutes BETWEEN 5 AND 480),
    price                  NUMERIC(10,2) CHECK (price IS NULL OR price >= 0),
    show_price             BOOLEAN NOT NULL DEFAULT TRUE,
    online_booking_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    active                 BOOLEAN NOT NULL DEFAULT TRUE,
    display_order          INTEGER NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT services_name_unique UNIQUE (clinic_id, name)
);
CREATE INDEX services_clinic_idx ON services (clinic_id, active, display_order);

-- Pacotes de sessões vendidos pela clínica (ex.: 10 sessões de Pilates).
CREATE TABLE session_packages (
    id             UUID PRIMARY KEY,
    clinic_id      UUID NOT NULL REFERENCES clinics(id),
    service_id     UUID NOT NULL REFERENCES services(id),
    name           VARCHAR(120) NOT NULL,
    session_count  INTEGER NOT NULL CHECK (session_count BETWEEN 1 AND 200),
    price          NUMERIC(10,2) CHECK (price IS NULL OR price >= 0),
    show_price     BOOLEAN NOT NULL DEFAULT TRUE,
    validity_days  INTEGER CHECK (validity_days IS NULL OR validity_days BETWEEN 1 AND 730),
    active         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT packages_name_unique UNIQUE (clinic_id, service_id, name)
);
CREATE INDEX packages_clinic_idx ON session_packages (clinic_id, service_id, active);
