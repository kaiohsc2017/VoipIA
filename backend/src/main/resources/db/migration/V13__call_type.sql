-- V13: Adiciona call_type em call_records para exibir Incidente/Requisição no dashboard
ALTER TABLE call_records ADD COLUMN IF NOT EXISTS call_type VARCHAR(50);
COMMENT ON COLUMN call_records.call_type IS 'Tipo do atendimento: Incidente, Requisição, etc. Extraído das respostas da URA.';
