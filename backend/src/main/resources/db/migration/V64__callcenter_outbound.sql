-- Fase 23 do plano omnicanal Parte III — chamadas de saída (ativo manual do agente).
-- direction distingue INBOUND (fila, comportamento existente) de OUTBOUND (dial direto do
-- ramal do agente, sem fila — queue_id já era nullable desde a V50, nenhuma mudança necessária
-- ali). Default INBOUND preenche todo o histórico existente sem exigir backfill manual.
ALTER TABLE cc_interactions ADD COLUMN direction VARCHAR(10) NOT NULL DEFAULT 'INBOUND';
CREATE INDEX idx_cc_interactions_direction ON cc_interactions(direction);

COMMENT ON COLUMN cc_interactions.direction IS 'INBOUND (fila) ou OUTBOUND (ativo manual do agente, sem fila) — Fase 23';
COMMENT ON COLUMN cc_interactions.ani IS 'Para INBOUND: número do cliente que chamou. Para OUTBOUND: número discado pelo agente (reaproveitada por ser o mesmo conceito — "o número externo desta interação")';

-- Agregado diário por agente (9b) ganha corte por direção — sem isso, uma chamada de saída
-- passaria a se misturar em "answered"/avg_talk_seconds do receptivo assim que existir a
-- primeira linha OUTBOUND em cc_interactions.
ALTER TABLE cc_agg_agent_daily ADD COLUMN outbound_placed INT NOT NULL DEFAULT 0;
ALTER TABLE cc_agg_agent_daily ADD COLUMN avg_outbound_talk_seconds NUMERIC(10, 2);

COMMENT ON COLUMN cc_agg_agent_daily.answered IS 'Interações INBOUND desse agente com answered_at neste dia (queued_at no dia) — Fase 23 restringiu a INBOUND, ver outbound_placed';
COMMENT ON COLUMN cc_agg_agent_daily.avg_talk_seconds IS 'Aproximação de TMA do receptivo (INBOUND): apenas tempo de conversação (ended_at - answered_at) das atendidas — Fase 23 restringiu a INBOUND, ver avg_outbound_talk_seconds';
COMMENT ON COLUMN cc_agg_agent_daily.outbound_placed IS 'Chamadas OUTBOUND desse agente efetivamente atendidas pelo destino neste dia (Fase 23)';
COMMENT ON COLUMN cc_agg_agent_daily.avg_outbound_talk_seconds IS 'Tempo médio de conversação (ended_at - answered_at) das chamadas OUTBOUND atendidas pelo destino (Fase 23)';
