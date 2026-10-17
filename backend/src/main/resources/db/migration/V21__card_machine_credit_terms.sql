-- Taxas específicas por prazo de crédito à vista; preservar as migrações já executadas.
CREATE TABLE card_machine_credit_terms (
    clinic_id UUID NOT NULL,
    machine_id UUID NOT NULL,
    days INTEGER NOT NULL CHECK (days BETWEEN 0 AND 60),
    fee NUMERIC(5,2) NOT NULL CHECK (fee BETWEEN 0 AND 30),
    PRIMARY KEY (machine_id, days),
    CONSTRAINT card_machine_credit_terms_machine_fk FOREIGN KEY (clinic_id, machine_id)
        REFERENCES card_machines (clinic_id, id)
);

-- Configuração legada: replicar o prazo/taxa à vista pré-existentes.
INSERT INTO card_machine_credit_terms (clinic_id, machine_id, days, fee)
SELECT clinic_id, id, credit_days, credit_fee FROM card_machines;

-- A versão anterior aceitava 2x a 12x usando a taxa de 1x quando não cadastrada.
-- Materializar somente para máquinas preexistentes mantém seu comportamento histórico.
INSERT INTO card_machine_fees (clinic_id, machine_id, installments, fee)
SELECT m.clinic_id, m.id, n.installments, m.credit_fee
FROM card_machines m CROSS JOIN generate_series(2, 12) AS n(installments)
ON CONFLICT (machine_id, installments) DO NOTHING;

-- Snapshot do prazo escolhido em cada pagamento à vista; não altera valores anteriores.
ALTER TABLE payments ADD COLUMN settlement_days INTEGER CHECK (settlement_days BETWEEN 0 AND 60);
