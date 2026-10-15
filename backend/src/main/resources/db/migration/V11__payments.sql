-- Pagamentos, maquininhas e "quem está devendo". Dinheiro sempre NUMERIC(10,2).

-- Repassar a taxa da maquininha ao paciente (Lei 13.455/2017 permite preço por forma de pagamento). Padrão: não.
ALTER TABLE clinics ADD COLUMN pass_card_fee BOOLEAN NOT NULL DEFAULT FALSE;

-- Valor da sessão avulsa, copiado do serviço quando a sessão é criada (mudar o preço do serviço não reescreve o
-- passado). A clínica pode ajustar uma sessão (desconto). Sessão em pacote é cobrada pelo pacote.
ALTER TABLE appointments ADD COLUMN price NUMERIC(10,2) CHECK (price IS NULL OR price >= 0);
CREATE FUNCTION appointments_snapshot_price() RETURNS trigger AS $$
BEGIN
    IF NEW.kind = 'SESSION' AND NEW.price IS NULL THEN
        SELECT s.price INTO NEW.price FROM services s WHERE s.id = NEW.service_id AND s.clinic_id = NEW.clinic_id;
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER appointments_price BEFORE INSERT ON appointments FOR EACH ROW EXECUTE FUNCTION appointments_snapshot_price();
UPDATE appointments a SET price = s.price FROM services s
 WHERE a.kind = 'SESSION' AND a.price IS NULL AND s.id = a.service_id AND s.clinic_id = a.clinic_id;

-- Maquininha de cartão: taxas em % por modalidade e prazo de recebimento.
CREATE TABLE card_machines (
    id            UUID PRIMARY KEY,
    clinic_id     UUID NOT NULL REFERENCES clinics(id),
    name          VARCHAR(60) NOT NULL,
    debit_fee     NUMERIC(5,2) NOT NULL DEFAULT 0 CHECK (debit_fee BETWEEN 0 AND 30),
    credit_fee    NUMERIC(5,2) NOT NULL DEFAULT 0 CHECK (credit_fee BETWEEN 0 AND 30),
    fixed_fee     NUMERIC(10,2) NOT NULL DEFAULT 0 CHECK (fixed_fee BETWEEN 0 AND 100),
    debit_days    INTEGER NOT NULL DEFAULT 1 CHECK (debit_days BETWEEN 0 AND 60),
    credit_days   INTEGER NOT NULL DEFAULT 30 CHECK (credit_days BETWEEN 0 AND 60),
    -- Antecipação: parcelado cai todo de uma vez (a taxa de antecipação já vem embutida na taxa cadastrada).
    anticipated   BOOLEAN NOT NULL DEFAULT FALSE,
    active        BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT card_machines_clinic_id_unique UNIQUE (clinic_id, id)
);

-- Taxa do crédito parcelado por nº de parcelas (2x a 12x). Sem linha para a parcela = usa a taxa do crédito à vista.
CREATE TABLE card_machine_fees (
    clinic_id     UUID NOT NULL,
    machine_id    UUID NOT NULL,
    installments  INTEGER NOT NULL CHECK (installments BETWEEN 2 AND 12),
    fee           NUMERIC(5,2) NOT NULL CHECK (fee BETWEEN 0 AND 30),
    PRIMARY KEY (machine_id, installments),
    CONSTRAINT card_machine_fees_machine_fk FOREIGN KEY (clinic_id, machine_id) REFERENCES card_machines (clinic_id, id)
);

ALTER TABLE patient_packages ADD CONSTRAINT patient_packages_patient_unique UNIQUE (clinic_id, patient_id, id);
ALTER TABLE appointments ADD CONSTRAINT appointments_patient_unique UNIQUE (clinic_id, patient_id, id);

-- Pagamento recebido do paciente. amount = quanto abate da dívida; charged_amount = quanto o paciente pagou de fato
-- (maior que amount quando a taxa é repassada); fee/net = o que a maquininha cobra e o que cai na conta.
-- Pode ser de um pacote ou de uma sessão avulsa do MESMO paciente (garantido pelo banco), ou sem vínculo.
-- Nada é apagado: estorno = canceled.
CREATE TABLE payments (
    id                  UUID PRIMARY KEY,
    clinic_id           UUID NOT NULL REFERENCES clinics(id),
    patient_id          UUID NOT NULL,
    patient_package_id  UUID,
    appointment_id      UUID,
    method              VARCHAR(10) NOT NULL CHECK (method IN ('DINHEIRO', 'PIX', 'DEBITO', 'CREDITO')),
    machine_id          UUID,
    installments        INTEGER NOT NULL DEFAULT 1 CHECK (installments BETWEEN 1 AND 12),
    amount              NUMERIC(10,2) NOT NULL CHECK (amount > 0),
    charged_amount      NUMERIC(10,2) NOT NULL CHECK (charged_amount >= amount),
    fee_percent         NUMERIC(5,2) NOT NULL DEFAULT 0,
    fee_amount          NUMERIC(10,2) NOT NULL DEFAULT 0 CHECK (fee_amount >= 0),
    net_amount          NUMERIC(10,2) NOT NULL,
    paid_on             DATE NOT NULL,
    notes               VARCHAR(300),
    canceled            BOOLEAN NOT NULL DEFAULT FALSE,
    canceled_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT payments_clinic_id_unique UNIQUE (clinic_id, id),
    CONSTRAINT payments_patient_fk FOREIGN KEY (clinic_id, patient_id) REFERENCES patients (clinic_id, id),
    CONSTRAINT payments_package_fk FOREIGN KEY (clinic_id, patient_id, patient_package_id)
        REFERENCES patient_packages (clinic_id, patient_id, id),
    CONSTRAINT payments_appointment_fk FOREIGN KEY (clinic_id, patient_id, appointment_id)
        REFERENCES appointments (clinic_id, patient_id, id),
    CONSTRAINT payments_machine_fk FOREIGN KEY (clinic_id, machine_id) REFERENCES card_machines (clinic_id, id),
    CONSTRAINT payments_one_target CHECK (patient_package_id IS NULL OR appointment_id IS NULL),
    CONSTRAINT payments_card_needs_machine CHECK ((method IN ('DEBITO', 'CREDITO')) = (machine_id IS NOT NULL)),
    CONSTRAINT payments_installments_credit CHECK (installments = 1 OR method = 'CREDITO')
);
CREATE INDEX payments_patient_idx ON payments (clinic_id, patient_id);
CREATE INDEX payments_paid_on_idx ON payments (clinic_id, paid_on);

-- O que a maquininha vai depositar (uma linha por parcela, ou uma só se antecipado/débito).
CREATE TABLE payment_receivables (
    id           UUID PRIMARY KEY,
    clinic_id    UUID NOT NULL,
    payment_id   UUID NOT NULL,
    installment  INTEGER NOT NULL CHECK (installment BETWEEN 1 AND 12),
    due_on       DATE NOT NULL,
    amount       NUMERIC(10,2) NOT NULL,
    received_on  DATE,
    CONSTRAINT payment_receivables_payment_fk FOREIGN KEY (clinic_id, payment_id) REFERENCES payments (clinic_id, id)
);
CREATE INDEX payment_receivables_due_idx ON payment_receivables (clinic_id, received_on, due_on);
