-- Logo da clínica, guardada no próprio banco (custo zero, sem storage externo). Tabela separada para que as
-- consultas da clínica não carreguem a imagem. O navegador já envia PNG redimensionado; o servidor revalida.
CREATE TABLE clinic_logos (
    clinic_id     UUID PRIMARY KEY REFERENCES clinics(id),
    content_type  VARCHAR(20) NOT NULL CHECK (content_type IN ('image/png', 'image/jpeg')),
    data          BYTEA NOT NULL CHECK (octet_length(data) <= 1048576),
    width         INTEGER NOT NULL CHECK (width BETWEEN 16 AND 2048),
    height        INTEGER NOT NULL CHECK (height BETWEEN 16 AND 2048),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
