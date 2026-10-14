-- Aparência do site público. "auto" = o sistema escolhe pelo tipo de serviço oferecido (ex.: Pilates → sereno).
ALTER TABLE clinics ADD COLUMN site_style VARCHAR(20) NOT NULL DEFAULT 'auto'
    CHECK (site_style IN ('auto', 'sereno', 'dinamico', 'acolhedor', 'clinico'));
-- Par de fontes (títulos + texto) de uma lista fechada de fontes gratuitas do Google Fonts.
ALTER TABLE clinics ADD COLUMN site_fonts VARCHAR(20) NOT NULL DEFAULT 'auto'
    CHECK (site_fonts IN ('auto', 'elegante', 'classica', 'moderna', 'geometrica', 'suave', 'profissional'));
-- Frase de destaque do topo do site; vazia = gerada a partir dos serviços.
ALTER TABLE clinics ADD COLUMN site_headline VARCHAR(140);
