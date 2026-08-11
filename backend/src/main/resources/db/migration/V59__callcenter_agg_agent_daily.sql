-- V59 — Sub-fase 9b do módulo Call Center: segundo agregado diário da Fase 9 (Relatórios
-- analíticos), desta vez por AGENTE de voz. Escopo desta fatia: ocupação/disponibilidade a
-- partir de cc_agent_states (Fase 4) + volume/TMA a partir de cc_interactions (já usado na
-- sub-fase 9a). Agregado de fluxo/URA, agregado de chat, aderência à escala (não existe conceito
-- de escala/turno no sistema ainda), rechamada 24h/7d, top motivos de tabulação e transferências
-- ficam para uma fatia 9c futura.
--
-- Um registro por (agente, dia) — reprocessável a qualquer momento, mesmo padrão de upsert via
-- índice único da V58.

CREATE TABLE cc_agg_agent_daily (
    id                BIGSERIAL PRIMARY KEY,
    agent_id          BIGINT NOT NULL REFERENCES cc_agents(id),
    date              DATE NOT NULL,
    business_unit_id  BIGINT REFERENCES business_units(id),
    answered          INT NOT NULL DEFAULT 0,
    avg_talk_seconds  NUMERIC(10, 2),
    occupied_seconds  INT NOT NULL DEFAULT 0,
    available_seconds INT NOT NULL DEFAULT 0,
    paused_seconds    INT NOT NULL DEFAULT 0,
    offline_seconds   INT NOT NULL DEFAULT 0,
    occupancy_pct     NUMERIC(5, 2),
    computed_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE cc_agg_agent_daily IS 'Agregado diário de volume/ocupação por agente de voz (Fase 9b) — recalculado via upsert (agent_id, date), nunca acumulado incrementalmente';
COMMENT ON COLUMN cc_agg_agent_daily.business_unit_id IS 'Herdada de cc_agents.business_unit_id no momento do cálculo — mesmo padrão de cc_agg_queue_daily.business_unit_id (V58)';
COMMENT ON COLUMN cc_agg_agent_daily.answered IS 'Interações desse agente com answered_at neste dia (queued_at no dia)';
COMMENT ON COLUMN cc_agg_agent_daily.avg_talk_seconds IS 'Aproximação de TMA: apenas tempo de conversação (ended_at - answered_at) das atendidas — mesma aproximação documentada em cc_agg_queue_daily (V58); ACW vira métrica própria de ocupação aqui, não é somado ao TMA';
COMMENT ON COLUMN cc_agg_agent_daily.occupied_seconds IS 'Soma de segundos em EM_ATENDIMENTO + ACW (cc_agent_states) dentro do dia — períodos que cruzam a meia-noite só contam a fatia dentro do dia';
COMMENT ON COLUMN cc_agg_agent_daily.available_seconds IS 'Soma de segundos em DISPONIVEL (cc_agent_states) dentro do dia';
COMMENT ON COLUMN cc_agg_agent_daily.paused_seconds IS 'Soma de segundos em PAUSA (cc_agent_states) dentro do dia';
COMMENT ON COLUMN cc_agg_agent_daily.offline_seconds IS 'Soma de segundos em OFFLINE (cc_agent_states) dentro do dia';
COMMENT ON COLUMN cc_agg_agent_daily.occupancy_pct IS 'occupied_seconds / (occupied_seconds + available_seconds) * 100 — null se o agente nunca esteve logado (Disponível/Em atendimento/ACW) nesse dia';

CREATE UNIQUE INDEX idx_cc_agg_agent_daily_agent_date ON cc_agg_agent_daily(agent_id, date);
