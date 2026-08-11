-- V43: expõe os campos restantes do XML Verint (grupos A/B/C do plano de MVP da tela
-- Chamadas) como colunas próprias em call_audio_files — hoje só existem dentro de
-- xml_raw (JSONB). Todas nullable: chamadas antigas (52 já ingeridas) recebem os valores
-- via backfill metadata-only (insights/src/backfill_metadata.py), sem reprocessar STT/LLM.
--
-- Também cria call_transfer_events (grupo D — feature de descobrir para qual ramal uma
-- chamada foi transferida): 0..N eventos por chamada, com correlação best-effort contra
-- outra gravação já ingerida via target_switch_call_id/switch_call_id (ver
-- TransferResolutionService) — a correlação normalmente NÃO resolve com o volume atual
-- de /opt/audio, e isso é esperado (resolved_at fica NULL).

ALTER TABLE call_audio_files
    -- Grupo A — Identificação
    ADD COLUMN customer_number VARCHAR(50),
    ADD COLUMN organization VARCHAR(100),
    -- Grupo B — Qualidade
    ADD COLUMN disconnected_by VARCHAR(20),
    ADD COLUMN number_of_holds INT,
    ADD COLUMN total_hold_time INT,
    ADD COLUMN number_of_transfers INT,
    ADD COLUMN number_of_conferences INT,
    ADD COLUMN wrapup_time INT,
    -- Grupo C — Técnico/Auditoria (admin-only, sempre só no detalhe)
    ADD COLUMN codec VARCHAR(20),
    ADD COLUMN missed_rtp_packets INT,
    ADD COLUMN decoding_errors INT,
    ADD COLUMN switch_call_id VARCHAR(50),
    ADD COLUMN trunk VARCHAR(20),
    ADD COLUMN capture_type VARCHAR(20),
    ADD COLUMN datasource_name VARCHAR(20);

COMMENT ON COLUMN call_audio_files.customer_number IS 'Número do cliente resolvido por direção: signallingcallingparty (inbound) ou calledparty/numberdialed (outbound) — ver decisão do plano insights-chamadas-campos-xml';
COMMENT ON COLUMN call_audio_files.disconnected_by IS 'atendente ou cliente — normalizado de disconnectingparty (EMPLOYEE/OTHER) pelo parser Python';
COMMENT ON COLUMN call_audio_files.switch_call_id IS 'ID de correlação técnico do PBX (session/switch_call_id) — também usado como alvo da correlação de transferência (ver call_transfer_events.target_switch_call_id)';

CREATE INDEX idx_call_audio_files_customer_number ON call_audio_files(customer_number);
CREATE INDEX idx_call_audio_files_switch_call_id ON call_audio_files(switch_call_id);

-- ─── call_transfer_events — 0..N eventos de transferência por chamada (grupo D) ───
CREATE TABLE call_transfer_events (
    id                     BIGSERIAL PRIMARY KEY,
    audio_file_id          BIGINT NOT NULL REFERENCES call_audio_files(id),
    transfer_order         SMALLINT NOT NULL,
    transferred_at         TIMESTAMP,
    disconnected_by        VARCHAR(20),
    target_switch_call_id  VARCHAR(50),
    target_extension       VARCHAR(20),
    target_agent_name      VARCHAR(100),
    target_audio_file_id   BIGINT REFERENCES call_audio_files(id),
    resolved_at            TIMESTAMP,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE call_transfer_events IS 'Evento de transferência extraído do XML Verint (par Begin_Call->Transferred) — target_switch_call_id é a chave de correlação com outra gravação já ingerida; resolved_at NULL = ainda não encontrou a perna de destino (estado normal, não erro)';
COMMENT ON COLUMN call_transfer_events.target_switch_call_id IS 'globalcallid capturado no Begin_Call imediatamente anterior ao Transferred — dado técnico, admin-only';

CREATE INDEX idx_call_transfer_events_audio_file ON call_transfer_events(audio_file_id);
CREATE INDEX idx_call_transfer_events_target_switch_call_id ON call_transfer_events(target_switch_call_id) WHERE resolved_at IS NULL;
