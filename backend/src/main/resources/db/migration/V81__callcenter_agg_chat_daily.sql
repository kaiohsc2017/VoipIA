-- V81 — Sub-fase 9c.2 do módulo Call Center (Fase 9, Relatórios analíticos): agregado diário de
-- chat, mesmo padrão de upsert por (queue_id, date) das V58/V59/V80. cc_chat_messages é
-- particionada por created_at (V71) — o service filtra sempre por essa coluna antes de buscar
-- mensagens de um lote de sessões, nunca varrendo todas as partições.

CREATE TABLE cc_agg_chat_daily (
    id                      BIGSERIAL PRIMARY KEY,
    queue_id                BIGINT NOT NULL REFERENCES cc_queues(id),
    date                    DATE NOT NULL,
    business_unit_id        BIGINT REFERENCES business_units(id),
    received                INT NOT NULL DEFAULT 0,
    claimed                 INT NOT NULL DEFAULT 0,
    closed                  INT NOT NULL DEFAULT 0,
    bot_contained           INT NOT NULL DEFAULT 0,
    bot_escalated           INT NOT NULL DEFAULT 0,
    avg_frt_seconds         NUMERIC(10, 2),
    avg_response_seconds    NUMERIC(10, 2),
    avg_concurrent_chats    NUMERIC(6, 2),
    computed_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE cc_agg_chat_daily IS 'Agregado diário de chat (Fase 9c.2) — recalculado via upsert (queue_id, date)';
COMMENT ON COLUMN cc_agg_chat_daily.business_unit_id IS 'Herdada de cc_queues.business_unit_id — SEM filtro de BU na leitura (D8 do plano, mesmo gap aceito dos demais agregados)';
COMMENT ON COLUMN cc_agg_chat_daily.received IS 'Sessões de chat com started_at neste dia, nesta fila';
COMMENT ON COLUMN cc_agg_chat_daily.claimed IS 'Dessas, quantas foram assumidas por um agente (claimed_at != null)';
COMMENT ON COLUMN cc_agg_chat_daily.closed IS 'Dessas, quantas já encerraram (closed_at != null) — pode ser menor que received, sessão pode encerrar em outro dia';
COMMENT ON COLUMN cc_agg_chat_daily.bot_contained IS 'Encerradas pelo próprio bot sem nunca escalar a um agente (claimed_at null, canal com bot configurado)';
COMMENT ON COLUMN cc_agg_chat_daily.bot_escalated IS 'Canal com bot configurado que escalou para atendimento humano (claimed_at != null)';
COMMENT ON COLUMN cc_agg_chat_daily.avg_frt_seconds IS 'First Response Time — média de (primeira mensagem com sender_type=agent) - started_at, só das sessões com pelo menos uma resposta de agente';
COMMENT ON COLUMN cc_agg_chat_daily.avg_response_seconds IS 'Average Response Time — média do intervalo entre uma mensagem do cliente e a próxima resposta (agente ou bot), em todas as sessões do dia';
COMMENT ON COLUMN cc_agg_chat_daily.avg_concurrent_chats IS 'Concorrência média ponderada pelo tempo — integral do número de sessões simultaneamente abertas ao longo do dia, dividida pela duração do dia (sweep-line sobre started_at/closed_at)';

CREATE UNIQUE INDEX idx_cc_agg_chat_daily_queue_date ON cc_agg_chat_daily(queue_id, date);
