-- V16: alarga colunas alimentadas por texto livre do STT (respostas da URA).
-- O STT pode retornar frases inteiras de fallback (ex: "Não foi detectada
-- nenhuma prioridade no áudio.") em vez de um valor curto — VARCHAR(20)
-- em priority já causou falha de INSERT (value too long) em produção.

ALTER TABLE call_records
    ALTER COLUMN priority       TYPE VARCHAR(255),
    ALTER COLUMN reported_ramal TYPE VARCHAR(255),
    ALTER COLUMN call_type      TYPE VARCHAR(255);
