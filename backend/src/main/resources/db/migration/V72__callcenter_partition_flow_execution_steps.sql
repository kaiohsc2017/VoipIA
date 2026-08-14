-- V72 — Particionamento de cc_flow_execution_steps (Fase 10, parte 2 — extensão do
-- particionamento da V71 para o traço de execução do Flow Builder/URA; pedido do usuário: "roda
-- o particionamento de fluxo/URA agora"). cc_flow_executions (uma linha por chamada que passa
-- pelo app Stasis "callcenter") NÃO é particionada nesta migration — cc_flow_execution_steps (o
-- traço nó a nó, N linhas por execução) é quem cresce rápido, mesmo padrão já usado na V71
-- (particiona o evento/traço filho, não o agregado pai).
--
-- Restrição técnica que decidiu o escopo: cc_flow_execution_steps.execution_id tem FK para
-- cc_flow_executions(id). O Postgres exige que toda chave primária/única de tabela particionada
-- inclua a coluna de particionamento — logo, se cc_flow_executions fosse particionada por
-- started_at, o PK viraria (id, started_at) e a coluna id sozinha deixaria de ter constraint
-- único (Postgres não permite mais um UNIQUE(id) isolado numa tabela particionada), quebrando
-- essa FK. Por isso só cc_flow_execution_steps é particionada aqui — é uma tabela folha, nada
-- referencia seu id por FK (confirmado antes desta migration).
--
-- Particularidade vs V71: cc_flow_execution_steps não é só INSERT — FlowExecutionTraceService
-- atualiza exited_at/taken_edge de um passo já existente via UPDATE ... WHERE id = ? (Hibernate
-- save() num id conhecido, sem entered_at). Isso não quebra (entered_at, a coluna de partição,
-- nunca é alterada — não há tentativa de mover linha entre partições), mas perde o pruning nesse
-- UPDATE: o Postgres varre o índice (id, entered_at) de cada uma das 37 partições em vez de uma
-- só. Aceitável no volume atual (0 linhas hoje, confirmado antes desta migration); documentado
-- para não surpreender quem for investigar lentidão de escrita no futuro.

DROP TABLE cc_flow_execution_steps;

CREATE TABLE cc_flow_execution_steps (
    id              BIGSERIAL,
    execution_id    BIGINT NOT NULL REFERENCES cc_flow_executions(id) ON DELETE CASCADE,
    node_id         VARCHAR(64) NOT NULL,
    node_type       VARCHAR(40) NOT NULL,
    entered_at      TIMESTAMP NOT NULL DEFAULT now(),
    exited_at       TIMESTAMP,
    taken_edge      VARCHAR(64),
    -- Nunca dado sensível/entrada livre do usuário — só metadados como o dígito de um menu
    -- (mesma ressalva já documentada na V53).
    detail          TEXT,
    PRIMARY KEY (id, entered_at)
) PARTITION BY RANGE (entered_at);

COMMENT ON TABLE cc_flow_execution_steps IS 'Traço nó a nó de uma execução de fluxo (Fase 5b) — particionada por mês (entered_at) desde a Fase 10/parte 2';

CREATE INDEX idx_cc_flow_execution_steps_execution_entered ON cc_flow_execution_steps(execution_id, entered_at);

DO $$
DECLARE
    d date := DATE '2025-01-01';
    d_end date := DATE '2028-01-01';
BEGIN
    WHILE d < d_end LOOP
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF cc_flow_execution_steps FOR VALUES FROM (%L) TO (%L)',
            'cc_flow_execution_steps_' || to_char(d, 'YYYY_MM'),
            d,
            (d + interval '1 month')::date
        );
        d := (d + interval '1 month')::date;
    END LOOP;
END $$;

CREATE TABLE cc_flow_execution_steps_default PARTITION OF cc_flow_execution_steps DEFAULT;
