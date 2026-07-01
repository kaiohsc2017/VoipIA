-- V15: novos campos filtráveis no dashboard de chamadas da URA
-- reported_ramal: ramal/telefone que o CLIENTE informa por voz na URA (customfield_telefone)
-- priority: impacto (Baixa/Média/Alta) extraído das respostas coletadas na URA

ALTER TABLE call_records
    ADD COLUMN reported_ramal VARCHAR(50),
    ADD COLUMN priority       VARCHAR(20);

CREATE INDEX idx_call_records_reported_ramal ON call_records(reported_ramal);
CREATE INDEX idx_call_records_priority       ON call_records(priority);
CREATE INDEX idx_call_records_client_name    ON call_records(client_name);
CREATE INDEX idx_call_records_call_type      ON call_records(call_type);
