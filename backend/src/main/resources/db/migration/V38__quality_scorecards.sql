-- V38: fichas de avaliação (scorecards) de qualidade — Fase 1 da evolução do módulo
-- Insights para Quality Management (paridade com NICE CXone / Verint AQM / Genesys Cloud QM).
--
-- Cada chamada processada com uma ficha ativa recebe uma nota por item, calculada pela IA e
-- validada/clampada deterministicamente pelo backend (nunca persistimos o número cru do LLM —
-- lição do bug real de overflow em call_insights.aderencia_script, ver V35). Editar uma ficha
-- em uso cria uma nova versão em vez de sobrescrever a existente, preservando o histórico de
-- avaliações já feitas com a versão anterior.

-- ─── quality_scorecards — fichas de avaliação cadastradas pelo administrador ───
CREATE TABLE quality_scorecards (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    description TEXT,
    is_active   BOOLEAN NOT NULL DEFAULT false,
    version     INT NOT NULL DEFAULT 1,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE  quality_scorecards IS 'Ficha de avaliação de qualidade — perguntas ponderadas usadas pela IA para notar cada chamada. Editar uma ficha em uso cria uma linha nova com version incrementada; a antiga fica inativa e preservada para não invalidar avaliações já feitas com ela';
COMMENT ON COLUMN quality_scorecards.is_active IS 'Só uma ficha pode estar ativa por vez (ver índice único parcial abaixo) — é a ficha usada pelo serviço asteriskia-insights para avaliar novas chamadas';
COMMENT ON COLUMN quality_scorecards.version IS 'Incrementada a cada nova versão da ficha com o mesmo propósito — call_evaluations.scorecard_id aponta para a versão exata usada na avaliação';

CREATE UNIQUE INDEX idx_quality_scorecards_one_active ON quality_scorecards(is_active) WHERE is_active = true;

CREATE OR REPLACE FUNCTION update_quality_scorecards_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_quality_scorecards_updated_at
    BEFORE UPDATE ON quality_scorecards
    FOR EACH ROW EXECUTE FUNCTION update_quality_scorecards_timestamp();

-- ─── scorecard_items — perguntas/critérios de cada ficha ───
CREATE TABLE scorecard_items (
    id           BIGSERIAL PRIMARY KEY,
    scorecard_id BIGINT NOT NULL REFERENCES quality_scorecards(id) ON DELETE CASCADE,
    ordem        INT NOT NULL,
    pergunta     TEXT NOT NULL,
    peso         NUMERIC(5,2) NOT NULL DEFAULT 1,
    nota_maxima  INT NOT NULL DEFAULT 10,
    is_critical  BOOLEAN NOT NULL DEFAULT false
);

COMMENT ON TABLE  scorecard_items IS 'Item/pergunta de uma ficha de avaliação, com peso na nota ponderada e nota máxima possível';
COMMENT ON COLUMN scorecard_items.is_critical IS 'Pergunta fatal/auto-fail: se a chamada zerar este item, a avaliação inteira é marcada is_failed independente da nota total (padrão "compliance trigger" do mercado de QM)';

CREATE INDEX idx_scorecard_items_scorecard ON scorecard_items(scorecard_id);

-- ─── call_evaluations — 1 linha por chamada avaliada contra uma ficha ───
CREATE TABLE call_evaluations (
    id             BIGSERIAL PRIMARY KEY,
    audio_file_id  BIGINT NOT NULL UNIQUE REFERENCES call_audio_files(id) ON DELETE CASCADE,
    scorecard_id   BIGINT NOT NULL REFERENCES quality_scorecards(id),
    nota_total     NUMERIC(5,2) NOT NULL,
    is_failed      BOOLEAN NOT NULL DEFAULT false,
    fail_reason    TEXT,
    llm_tokens_in  INT NOT NULL DEFAULT 0,
    llm_tokens_out INT NOT NULL DEFAULT 0,
    llm_model      VARCHAR(100),
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE  call_evaluations IS 'Resultado consolidado da avaliação de uma chamada contra uma ficha — nota_total é a soma ponderada normalizada em 0-100, calculada deterministicamente pelo backend a partir das notas por item (nunca aceita o total já pronto do LLM)';
COMMENT ON COLUMN call_evaluations.is_failed IS 'true quando algum item is_critical=true recebeu nota 0 — reprovação por regra fatal, independente da nota_total';
COMMENT ON COLUMN call_evaluations.fail_reason IS 'Descrição de qual item crítico causou a reprovação, para exibição direta na UI sem precisar juntar com call_evaluation_items';

CREATE INDEX idx_call_evaluations_nota      ON call_evaluations(nota_total);
CREATE INDEX idx_call_evaluations_is_failed ON call_evaluations(is_failed);

-- ─── call_evaluation_items — nota/justificativa por item da ficha aplicada à chamada ───
CREATE TABLE call_evaluation_items (
    id                BIGSERIAL PRIMARY KEY,
    evaluation_id     BIGINT NOT NULL REFERENCES call_evaluations(id) ON DELETE CASCADE,
    item_id           BIGINT NOT NULL REFERENCES scorecard_items(id),
    nota              NUMERIC(5,2) NOT NULL,
    justificativa     TEXT,
    trecho_referencia TEXT
);

COMMENT ON TABLE  call_evaluation_items IS 'Nota atribuída pela IA a um item específico da ficha nesta chamada, com justificativa e trecho da transcrição que embasa a nota — mesmo princípio de evidência ancorada de call_insight_findings.trecho_referencia';
COMMENT ON COLUMN call_evaluation_items.nota IS 'Sempre clampada em [0, scorecard_items.nota_maxima] pelo backend antes de persistir — o LLM pode devolver valor fora da escala';

CREATE INDEX idx_call_evaluation_items_evaluation ON call_evaluation_items(evaluation_id);
