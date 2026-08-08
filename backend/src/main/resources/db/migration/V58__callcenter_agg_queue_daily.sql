-- V58 — Sub-fase 9a do módulo Call Center: primeiro agregado diário da Fase 9 (Relatórios
-- analíticos). Escopo desta fatia: só fila de voz (cc_interactions) — agregados de
-- agente/fluxo/chat ficam para fatias 9b/9c futuras (mesmo padrão de fatiamento já usado nas
-- Fases 7a/7b/8 desta sessão).
--
-- Um registro por (fila, dia) — reprocessável a qualquer momento (job noturno consolida o dia
-- anterior; reprocessamento manual sob demanda refaz um intervalo) sem duplicar linha, graças
-- ao índice único abaixo usado como chave de upsert.

CREATE TABLE cc_agg_queue_daily (
    id                BIGSERIAL PRIMARY KEY,
    queue_id          BIGINT NOT NULL REFERENCES cc_queues(id),
    date              DATE NOT NULL,
    business_unit_id  BIGINT REFERENCES business_units(id),
    received          INT NOT NULL DEFAULT 0,
    answered          INT NOT NULL DEFAULT 0,
    abandoned         INT NOT NULL DEFAULT 0,
    avg_wait_seconds  NUMERIC(10, 2),
    avg_talk_seconds  NUMERIC(10, 2),
    service_level_pct NUMERIC(5, 2),
    computed_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE cc_agg_queue_daily IS 'Agregado diário de volume/tempo/nível de serviço por fila de voz (Fase 9a) — recalculado via upsert (queue_id, date), nunca acumulado incrementalmente';
COMMENT ON COLUMN cc_agg_queue_daily.received IS 'Toda interação com queued_at neste dia, atendida ou não';
COMMENT ON COLUMN cc_agg_queue_daily.avg_wait_seconds IS 'ASA — só sobre interações atendidas (answered_at - queued_at)';
COMMENT ON COLUMN cc_agg_queue_daily.avg_talk_seconds IS 'Aproximação de TMA: apenas tempo de conversação (ended_at - answered_at) das atendidas — sem hold/ACW somados, porque ACW hoje é um estado do AGENTE (cc_agent_states), não da interação; juntar isso com precisão fica para uma fatia futura';
COMMENT ON COLUMN cc_agg_queue_daily.service_level_pct IS 'Percentual de atendidas com espera <= cc_queues.timeout_seconds da fila no momento do cálculo';

CREATE UNIQUE INDEX idx_cc_agg_queue_daily_queue_date ON cc_agg_queue_daily(queue_id, date);
