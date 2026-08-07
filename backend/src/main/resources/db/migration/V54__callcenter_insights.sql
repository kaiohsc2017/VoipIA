-- V54 — Fase 8 do módulo Call Center: reuso do pipeline de Insights (STT/análise de IA) para
-- as gravações do Call Center em /opt/telecom/gravacao. A coluna `source` de call_audio_files
-- já existe desde a V40 (discriminador verint/upload, sem CHECK constraint) — esta migration só
-- acrescenta o vínculo com cc_recordings (para a tela de Insights do Call Center linkar de volta
-- à gravação/interação/fila de origem) e a nova frente de custo de IA no Financeiro.

ALTER TABLE call_audio_files
    ADD COLUMN cc_recording_id BIGINT UNIQUE REFERENCES cc_recordings(id);

COMMENT ON COLUMN call_audio_files.cc_recording_id IS 'Vínculo com a gravação do Call Center que originou este registro (source=callcenter) — null para verint/upload';

-- Liga a gravação à interação formal (cc_interactions), promessa deixada em aberto pelo
-- comentário da V49/V50 ("preenchida quando a gravação correspondente for ingerida") — fechada
-- agora que o pipeline de Insights do Call Center depende desse vínculo para obter agente/fila.
-- (cc_recordings.interaction_id já existe desde a V50; nenhuma coluna nova aqui.)

-- Nova frente de custo de IA no Financeiro — mesmo padrão de V42.
ALTER TABLE financeiro_cost_alerts DROP CONSTRAINT chk_financeiro_cost_alerts_scope;
ALTER TABLE financeiro_cost_alerts
    ADD CONSTRAINT chk_financeiro_cost_alerts_scope CHECK (scope IN ('ura', 'insights', 'envios', 'callcenter'));

INSERT INTO financeiro_cost_alerts (scope, threshold_usd, enabled) VALUES ('callcenter', 0, FALSE);
