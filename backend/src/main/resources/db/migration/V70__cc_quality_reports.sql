-- Fase 26 do plano Call Center Parte III — relatório de qualidade.
-- Agrega dados já existentes (call_evaluations/call_evaluation_items, Fase 8) — nenhuma chamada
-- de IA nova, nenhuma coluna de custo.

-- Calendário de feriados — compartilhado com a Fase 5e (horário de funcionamento/transbordo do
-- Flow Builder, ainda não implementada), para não construir duas tabelas de feriado no projeto.
CREATE TABLE cc_holidays (
    id            SERIAL PRIMARY KEY,
    holiday_date  DATE NOT NULL UNIQUE,
    description   VARCHAR(200),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- source (verint|callcenter) desde o início — não repete o gap de agent_evolution_snapshots
-- (V39), que não tem essa coluna e mistura os dois universos quando o nome coincide.
CREATE TABLE cc_quality_reports (
    id                  BIGSERIAL PRIMARY KEY,
    source              VARCHAR(20) NOT NULL DEFAULT 'callcenter',
    scope_type          VARCHAR(10) NOT NULL,   -- AGENT | QUEUE | GERAL
    scope_value         VARCHAR(200),           -- nome do agente/fila; nulo para GERAL
    date_from           DATE NOT NULL,
    date_to             DATE NOT NULL,
    requested_by        VARCHAR(100) NOT NULL,
    requested_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    nota_media          NUMERIC(5,2),
    total_avaliacoes    INT NOT NULL DEFAULT 0,
    total_reprovadas    INT NOT NULL DEFAULT 0,
    previous_report_id  BIGINT REFERENCES cc_quality_reports(id),
    content_json        JSONB NOT NULL,
    -- BUs efetivamente agregadas nesta execução (lista separada por vírgula) — nulo quando quem
    -- gerou não tinha restrição de BU (ADMIN). Sem isso, a releitura (list/getById) não teria como
    -- saber se o conteúdo já calculado inclui dado de BU que o leitor atual não deveria ver —
    -- achado real de revisão de segurança, corrigido antes do deploy (ver CcQualityReportService).
    scoped_bu_ids       VARCHAR(500),
    CONSTRAINT chk_cc_quality_reports_scope_type CHECK (scope_type IN ('AGENT', 'QUEUE', 'GERAL'))
);
CREATE INDEX idx_cc_quality_reports_scope ON cc_quality_reports(scope_type, scope_value, requested_at DESC);

-- Ponto de evolução por item (mesmo padrão de agent_evolution_snapshots, V39) — item_id nulo
-- representa a nota total geral daquela execução.
CREATE TABLE cc_quality_report_snapshots (
    id           BIGSERIAL PRIMARY KEY,
    report_id    BIGINT NOT NULL REFERENCES cc_quality_reports(id) ON DELETE CASCADE,
    scope_type   VARCHAR(10) NOT NULL,
    scope_value  VARCHAR(200),
    item_id      BIGINT,
    metric_key   VARCHAR(50) NOT NULL,
    valor        NUMERIC(6,2) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_cc_quality_snapshots_scope ON cc_quality_report_snapshots(scope_type, scope_value, created_at);
