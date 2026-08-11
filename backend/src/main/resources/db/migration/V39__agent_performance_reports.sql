-- V39: relatórios de performance por atendente — Fase 2 da evolução do módulo Insights
-- para Quality Management (V38 fichas → V39 relatórios). Agregação (médias, evolução,
-- piores itens) é sempre calculada em SQL/Java; o LLM só escreve a narrativa a partir do
-- agregado (mesmo princípio de EvaluationService: nunca aceitar número pronto do LLM).
--
-- Cooldown: 1 relatório por atendente a cada 5 dias úteis, por par (requested_by,
-- agent_name) — ADMIN isento (checagem de aplicação em AgentReportService). O índice
-- único parcial abaixo é só um cinturão de segurança contra corrida de dois pedidos
-- simultâneos do mesmo par, não substitui a checagem de dias úteis.

CREATE TABLE agent_performance_reports (
    id                 BIGSERIAL PRIMARY KEY,
    agent_name         VARCHAR(200) NOT NULL,
    date_from          DATE NOT NULL,
    date_to            DATE NOT NULL,
    requested_by       VARCHAR(100) NOT NULL,
    requested_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    status             VARCHAR(20) NOT NULL DEFAULT 'pending',
    error_msg          TEXT,
    content_json       JSONB,
    llm_tokens_in      INT NOT NULL DEFAULT 0,
    llm_tokens_out     INT NOT NULL DEFAULT 0,
    llm_model          VARCHAR(100),
    completed_at       TIMESTAMPTZ,
    previous_report_id BIGINT REFERENCES agent_performance_reports(id),
    evolution_json     JSONB
);

COMMENT ON TABLE  agent_performance_reports IS 'Relatório de performance de um atendente num período, pedido por um supervisor (requested_by=username) — posse aplicada no service (não-ADMIN só vê os que pediu)';
COMMENT ON COLUMN agent_performance_reports.requested_by IS 'Username de quem pediu (JWT não tem user-id, só sub) — todo filtro de posse é por esta coluna';
COMMENT ON COLUMN agent_performance_reports.previous_report_id IS 'Último relatório done do mesmo agent_name (de qualquer solicitante) no momento do pedido, resolvido antes de enfileirar — usado para a seção de evolução';
COMMENT ON COLUMN agent_performance_reports.evolution_json IS 'Delta numérico por item entre este relatório e previous_report_id, calculado em Java; o LLM só narra o delta já calculado. Sinaliza comparação parcial se a versão da ficha divergir entre os dois';

CREATE INDEX idx_agent_reports_status ON agent_performance_reports(status);
CREATE INDEX idx_agent_reports_requested_at ON agent_performance_reports(requested_at);
CREATE INDEX idx_agent_reports_cooldown_lookup ON agent_performance_reports(requested_by, agent_name, requested_at DESC);

-- Cinturão de segurança: no máximo um pedido em voo (pending/processing) por par
-- solicitante+atendente — evita duplo processamento em caso de duplo clique/corrida.
CREATE UNIQUE INDEX idx_agent_reports_inflight_unique
    ON agent_performance_reports(requested_by, agent_name)
    WHERE status IN ('pending', 'processing');

-- ─── agent_evolution_snapshots — série temporal navegável independente de relatório ───
CREATE TABLE agent_evolution_snapshots (
    id          BIGSERIAL PRIMARY KEY,
    agent_name  VARCHAR(200) NOT NULL,
    report_id   BIGINT NOT NULL REFERENCES agent_performance_reports(id) ON DELETE CASCADE,
    item_id     BIGINT REFERENCES scorecard_items(id),
    metric_key  VARCHAR(100) NOT NULL,
    valor       NUMERIC(10,2) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE agent_evolution_snapshots IS 'Ponto de série temporal por agente/métrica — permite ao supervisor navegar o histórico de evolução do agente sem precisar abrir um relatório específico. item_id nullable cobre métricas sem ficha (ex: nota_total, sentimento_medio)';

CREATE INDEX idx_agent_evolution_snapshots_agent ON agent_evolution_snapshots(agent_name, created_at);
CREATE INDEX idx_agent_evolution_snapshots_report ON agent_evolution_snapshots(report_id);
