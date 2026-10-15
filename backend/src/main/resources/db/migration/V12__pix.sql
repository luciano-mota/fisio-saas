-- Pix sem intermediário: a clínica cadastra a chave; o sistema gera o "copia e cola" com valor e código do pedido.

ALTER TABLE clinics ADD COLUMN pix_key_type VARCHAR(10) CHECK (pix_key_type IN ('CPF', 'CNPJ', 'TELEFONE', 'EMAIL', 'ALEATORIA'));
ALTER TABLE clinics ADD COLUMN pix_key VARCHAR(77);
ALTER TABLE clinics ADD COLUMN pix_name VARCHAR(25);
ALTER TABLE clinics ADD COLUMN pix_city VARCHAR(15);
ALTER TABLE clinics ADD CONSTRAINT clinics_pix_complete CHECK ((pix_key IS NULL) = (pix_key_type IS NULL));

-- Pagamento escolhido no agendamento pelo site. PIX: paga agora e envia comprovante; CLINICA: paga no atendimento.
-- code = identificador curto do pedido (vai no Pix e ajuda a achar o pagamento no extrato).
ALTER TABLE booking_requests ADD COLUMN pay_method VARCHAR(10) CHECK (pay_method IN ('PIX', 'CLINICA'));
ALTER TABLE booking_requests ADD COLUMN pay_amount NUMERIC(10,2) CHECK (pay_amount IS NULL OR pay_amount > 0);
ALTER TABLE booking_requests ADD COLUMN code VARCHAR(12);
-- Só quem fez o pedido envia o comprovante (o token vai para o navegador dele; aqui fica só o hash).
ALTER TABLE booking_requests ADD COLUMN receipt_token_hash CHAR(64);
ALTER TABLE booking_requests ADD COLUMN payment_id UUID;
ALTER TABLE booking_requests ADD CONSTRAINT booking_requests_clinic_id_unique UNIQUE (clinic_id, id);
ALTER TABLE booking_requests ADD CONSTRAINT booking_requests_payment_fk FOREIGN KEY (clinic_id, payment_id) REFERENCES payments (clinic_id, id);
ALTER TABLE booking_requests ADD CONSTRAINT booking_requests_pix_amount CHECK (pay_method IS DISTINCT FROM 'PIX' OR pay_amount IS NOT NULL);

-- Comprovante do Pix (imagem ou PDF). Acesso só da responsável pela clínica (dado financeiro — LGPD).
CREATE TABLE booking_receipts (
    request_id    UUID PRIMARY KEY,
    clinic_id     UUID NOT NULL,
    content_type  VARCHAR(20) NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png', 'application/pdf')),
    data          BYTEA NOT NULL,
    size_bytes    INTEGER NOT NULL CHECK (size_bytes BETWEEN 1 AND 3145728),
    uploaded_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT booking_receipts_request_fk FOREIGN KEY (clinic_id, request_id) REFERENCES booking_requests (clinic_id, id)
);
