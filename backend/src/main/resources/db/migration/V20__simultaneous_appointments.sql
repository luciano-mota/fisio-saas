-- Atendimentos no mesmo horário: a clínica define quantas pessoas atende ao mesmo tempo (padrão 1 = como antes).
ALTER TABLE clinics ADD COLUMN max_simultaneous INT NOT NULL DEFAULT 1
    CONSTRAINT clinics_max_simultaneous CHECK (max_simultaneous BETWEEN 1 AND 20);

-- A regra "um por horário" sai do banco e vira contagem na aplicação (com trava por clínica na transação).
-- Bloqueios continuam fechando o horário para tudo.
ALTER TABLE appointments DROP CONSTRAINT appointments_no_overlap;
ALTER TABLE booking_requests DROP CONSTRAINT booking_requests_no_overlap;
