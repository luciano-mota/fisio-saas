-- Financeiro dos assinantes: plano por clínica (valor e dia de vencimento), mensalidades e Pix da plataforma.
ALTER TABLE clinics
    ADD COLUMN plan_price  NUMERIC(10, 2) CHECK (plan_price IS NULL OR plan_price >= 0),
    ADD COLUMN billing_day SMALLINT CHECK (billing_day IS NULL OR billing_day BETWEEN 1 AND 28);

-- Mensalidade: A PAGAR (OPEN) → a clínica informa que pagou (INFORMED: pendente nos dois lados) → a plataforma
-- confirma (PAID: receita da plataforma e despesa da clínica). CANCELED com motivo. Nada é apagado.
CREATE TABLE subscription_charges (
    id              UUID PRIMARY KEY,
    clinic_id       UUID NOT NULL REFERENCES clinics(id),
    reference_month DATE NOT NULL,                       -- primeiro dia do mês de referência
    description     VARCHAR(120) NOT NULL,
    amount          NUMERIC(10, 2) NOT NULL CHECK (amount > 0),
    due_on          DATE NOT NULL,
    status          VARCHAR(10) NOT NULL CHECK (status IN ('OPEN', 'INFORMED', 'PAID', 'CANCELED')),
    informed_at     TIMESTAMPTZ,
    informed_by     VARCHAR(255),
    paid_on         DATE,
    paid_amount     NUMERIC(10, 2),
    method          VARCHAR(10) CHECK (method IS NULL OR method IN ('PIX', 'CARTAO', 'BOLETO', 'DINHEIRO', 'OUTRO')),
    confirmed_by    VARCHAR(255),
    confirmed_at    TIMESTAMPTZ,
    notes           VARCHAR(300),
    created_by      VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX subscription_charges_clinic ON subscription_charges (clinic_id, due_on DESC);
-- Uma mensalidade por clínica e mês (as canceladas não contam).
CREATE UNIQUE INDEX subscription_charges_month ON subscription_charges (clinic_id, reference_month)
    WHERE status <> 'CANCELED';

-- Dados de recebimento da plataforma (linha única).
CREATE TABLE platform_settings (
    id           SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    pix_key_type VARCHAR(10),
    pix_key      VARCHAR(77),
    pix_name     VARCHAR(60),
    pix_city     VARCHAR(40),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO platform_settings(id) VALUES (1);
