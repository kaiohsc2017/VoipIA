-- V8: Adiciona operation_id na tabela alert_contacts

ALTER TABLE alert_contacts
ADD COLUMN operation_id BIGINT;

ALTER TABLE alert_contacts
ADD CONSTRAINT fk_alert_contact_operation
FOREIGN KEY (operation_id) REFERENCES operations(id)
ON DELETE SET NULL;
