-- Mensagem de aniversário da clínica (modelo editável). NULL = usa o modelo padrão do sistema.
ALTER TABLE clinics ADD COLUMN birthday_message VARCHAR(1000);
