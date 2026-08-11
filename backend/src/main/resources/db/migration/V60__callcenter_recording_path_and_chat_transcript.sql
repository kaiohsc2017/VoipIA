-- V60 — Fase 11 do módulo Call Center: padronização dos caminhos de gravação
-- (/opt/telecom/gravacao -> /opt/gravacoes/audio) e transcript de sessão de chat em disco
-- (/opt/gravacoes/chat).
--
-- O UPDATE abaixo é cosmético, não funcional: CallCenterRecordingService.resolveAudioFile usa
-- apenas o nome-base do file_path persistido e reconstrói o subdiretório yyyy/MM/dd a partir de
-- started_at + a base path da configuração corrente (app.callcenter.recording-path) — a leitura
-- do arquivo já funciona sem esta migration. Ela existe só para o dado não mentir sobre onde o
-- arquivo físico está depois que os arquivos forem movidos (scripts/migrar-gravacoes.sh).
UPDATE cc_recordings
SET file_path = replace(file_path, '/opt/telecom/gravacao', '/opt/gravacoes/audio')
WHERE file_path LIKE '/opt/telecom/gravacao%';

-- Caminho do transcript exportado ao encerrar a sessão de chat (Fase 11.3) — nulo até a
-- primeira sessão ser encerrada depois deste deploy; sessões já encerradas antes não são
-- reexportadas retroativamente.
ALTER TABLE cc_chat_sessions ADD COLUMN transcript_path VARCHAR(255);

COMMENT ON COLUMN cc_chat_sessions.transcript_path IS 'Caminho do transcript (.json/.txt) exportado em /opt/gravacoes/chat ao encerrar a sessão (CcChatService.close) — export assíncrono e tolerante a falha, nunca bloqueia o encerramento; null até a exportação concluir';
