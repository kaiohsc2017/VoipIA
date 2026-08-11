-- V44: coluna agent_login_id em call_audio_files — login do agente no PBX/Avaya (tag "agentid"
-- do XML Verint, igual ao elemento session/pbx_login_id), diferente do agent_id_verint já
-- existente (chave interna da própria Verint) e do extension (ramal). Nullable: chamadas já
-- ingeridas recebem o valor via backfill metadata-only (insights/src/backfill_metadata.py).

ALTER TABLE call_audio_files
    ADD COLUMN agent_login_id VARCHAR(20);

COMMENT ON COLUMN call_audio_files.agent_login_id IS 'Login do agente no PBX (tag agentid / elemento session/pbx_login_id) — distinto de agent_id_verint (chave interna Verint) e de extension (ramal)';

CREATE INDEX idx_call_audio_files_agent_login_id ON call_audio_files(agent_login_id);
