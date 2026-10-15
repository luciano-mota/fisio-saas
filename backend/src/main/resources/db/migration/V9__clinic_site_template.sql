-- Modelo do site (estrutura das seções) e clima (fundo e texto). Listas fechadas, como estilo e fontes.
ALTER TABLE clinics ADD COLUMN site_template VARCHAR(20) NOT NULL DEFAULT 'auto'
    CHECK (site_template IN ('auto', 'classico', 'imersivo', 'editorial', 'acolhedor'));
ALTER TABLE clinics ADD COLUMN site_mood VARCHAR(10) NOT NULL DEFAULT 'suave'
    CHECK (site_mood IN ('claro', 'suave', 'escuro'));
