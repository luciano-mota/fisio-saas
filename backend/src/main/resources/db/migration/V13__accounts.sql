-- Conta e equipe: senha temporária (quem recebe acesso troca no primeiro login) e data da última troca.
ALTER TABLE users ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN password_changed_at TIMESTAMPTZ;

-- Cadastro da clínica pelo próprio site: aceite dos termos de uso e da política de privacidade (versão aceita).
ALTER TABLE clinics ADD COLUMN terms_accepted_at TIMESTAMPTZ;
ALTER TABLE clinics ADD COLUMN terms_version VARCHAR(20);
