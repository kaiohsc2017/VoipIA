-- V80 — Sub-fase 9c.1 do módulo Call Center (Fase 9, Relatórios analíticos): agregado diário de
-- fluxo/URA (Flow Builder, Fase 5b), mesmo padrão de upsert por (flow_id, date) das V58/V59.
--
-- cc_agg_flow_daily: volume/desfecho por execução (cc_flow_executions).
-- cc_agg_flow_node_daily: abandono por nó (cc_flow_execution_steps) — quantas execuções entraram
-- em cada nó e quantas delas morreram ali (outcome ABANDONED/ERROR com last_node_id = node_id).

CREATE TABLE cc_agg_flow_daily (
    id                          BIGSERIAL PRIMARY KEY,
    flow_id                     BIGINT NOT NULL REFERENCES cc_flows(id),
    date                        DATE NOT NULL,
    business_unit_id            BIGINT REFERENCES business_units(id),
    executions                  INT NOT NULL DEFAULT 0,
    completed                   INT NOT NULL DEFAULT 0,
    transferred_queue           INT NOT NULL DEFAULT 0,
    transferred_extension       INT NOT NULL DEFAULT 0,
    abandoned                   INT NOT NULL DEFAULT 0,
    errored                     INT NOT NULL DEFAULT 0,
    avg_duration_seconds        NUMERIC(10, 2),
    computed_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE cc_agg_flow_daily IS 'Agregado diário de volume/desfecho de execuções de fluxo visual (Fase 9c.1) — recalculado via upsert (flow_id, date)';
COMMENT ON COLUMN cc_agg_flow_daily.business_unit_id IS 'Herdada de cc_flows.business_unit_id no momento do cálculo — SEM filtro de BU na leitura (D8 do plano, mesmo gap aceito de cc_agg_queue_daily/cc_agg_agent_daily)';
COMMENT ON COLUMN cc_agg_flow_daily.executions IS 'Total de cc_flow_executions com started_at neste dia, para este fluxo';
COMMENT ON COLUMN cc_agg_flow_daily.completed IS 'Execuções com outcome=COMPLETED (nó "encerrar")';
COMMENT ON COLUMN cc_agg_flow_daily.transferred_queue IS 'Execuções com outcome=TRANSFERRED_QUEUE (nó "enviar_fila")';
COMMENT ON COLUMN cc_agg_flow_daily.transferred_extension IS 'Execuções com outcome=TRANSFERRED_EXTENSION (nó "transferir_ramal", Fase 5e.2)';
COMMENT ON COLUMN cc_agg_flow_daily.abandoned IS 'Execuções com outcome=ABANDONED (nó terminal sem tratamento explícito de desfecho, ou execução ainda sem ended_at ao fim do dia — contada como abandonada em aberto)';
COMMENT ON COLUMN cc_agg_flow_daily.errored IS 'Execuções com outcome=ERROR (falha do motor — aresta inexistente, fallback de fila indisponível)';
COMMENT ON COLUMN cc_agg_flow_daily.avg_duration_seconds IS 'Média de (ended_at - started_at) das execuções já encerradas neste dia; null se nenhuma encerrou';

CREATE UNIQUE INDEX idx_cc_agg_flow_daily_flow_date ON cc_agg_flow_daily(flow_id, date);

CREATE TABLE cc_agg_flow_node_daily (
    id              BIGSERIAL PRIMARY KEY,
    flow_id         BIGINT NOT NULL REFERENCES cc_flows(id),
    node_id         VARCHAR(64) NOT NULL,
    node_type       VARCHAR(40) NOT NULL,
    date            DATE NOT NULL,
    entries         INT NOT NULL DEFAULT 0,
    abandoned_here  INT NOT NULL DEFAULT 0,
    computed_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE cc_agg_flow_node_daily IS 'Abandono por nó do fluxo (Fase 9c.1) — quantas execuções entraram em cada nó e quantas morreram ali (last_node_id do nó com outcome ABANDONED/ERROR)';
COMMENT ON COLUMN cc_agg_flow_node_daily.entries IS 'Quantidade de cc_flow_execution_steps deste nó, neste dia, para este fluxo (entered_at no dia)';
COMMENT ON COLUMN cc_agg_flow_node_daily.abandoned_here IS 'Dessas entradas, quantas são o last_node_id de uma execução cujo outcome final é ABANDONED ou ERROR (ou ainda sem outcome/ended_at ao fim do dia)';

CREATE UNIQUE INDEX idx_cc_agg_flow_node_daily_flow_node_date ON cc_agg_flow_node_daily(flow_id, node_id, date);
