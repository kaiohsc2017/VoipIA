-- V55 — Fase 8 do módulo Call Center: relatórios de performance por atendente (V39) passam
-- a existir também para o Call Center, cujas gravações agora alimentam o mesmo pipeline de
-- Insights (V54) com nomes de agente que podem coincidir com os do Verint. Sem uma coluna de
-- origem, um relatório pedido para "João" misturaria chamadas de Verint e de Call Center sob
-- o mesmo agent_name — a coluna `source` separa os dois universos em toda consulta de
-- agregação, cooldown e listagem (mesmo discriminador já usado em call_audio_files desde a V40).

ALTER TABLE agent_performance_reports
    ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'verint';

ALTER TABLE agent_performance_reports
    ADD CONSTRAINT chk_agent_performance_reports_source CHECK (source IN ('verint', 'callcenter'));

COMMENT ON COLUMN agent_performance_reports.source IS 'Origem das chamadas agregadas (verint|callcenter) — nunca mistura os dois universos num mesmo relatório, mesmo que o agent_name coincida';

-- Índices de cooldown/inflight recriados incluindo `source` — um supervisor pode pedir
-- relatório do mesmo agent_name em paralelo em Verint e em Call Center sem colidir.
DROP INDEX idx_agent_reports_cooldown_lookup;
CREATE INDEX idx_agent_reports_cooldown_lookup ON agent_performance_reports(requested_by, agent_name, source, requested_at DESC);

DROP INDEX idx_agent_reports_inflight_unique;
CREATE UNIQUE INDEX idx_agent_reports_inflight_unique
    ON agent_performance_reports(requested_by, agent_name, source)
    WHERE status IN ('pending', 'processing');
