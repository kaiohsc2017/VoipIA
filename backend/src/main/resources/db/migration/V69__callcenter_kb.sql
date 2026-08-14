-- V69 — Fase 25 do plano Call Center Parte III (IA de autosserviço no chat, D22/D22b).
--
-- Base de conhecimento própria do Call Center (artigos + fontes externas por URL), indexada por
-- embeddings locais (CPU, sem custo de token) num servidor HTTP interno novo no container
-- `insights` (Python) — modelo multilingue leve (384 dimensões), escolhido em vez do BGE-m3
-- originalmente cogitado no plano por causa da memória limitada desta VPS de dev/homologação
-- (3.8GB total, já sob pressão de swap). Reavaliar modelo maior quando o volume real justificar
-- servidor dedicado (mesma decisão já registrada para o dimensionamento de hardware do módulo).
--
-- A extensão pgvector já foi habilitada manualmente nesta VPS ao trocar a imagem do Postgres
-- para pgvector/pgvector:pg16 — o CREATE EXTENSION abaixo é idempotente, cobre qualquer ambiente
-- que aplique esta migration do zero.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE cc_kb_articles (
    id              BIGSERIAL PRIMARY KEY,
    title           VARCHAR(200) NOT NULL,
    body            TEXT NOT NULL,
    tags            VARCHAR(500),
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    version         INTEGER NOT NULL DEFAULT 1,
    indexed_version INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE cc_kb_articles IS
    'Artigo da base de conhecimento própria do Call Center (Fase 25, D22) — indexado por '
    'CallCenterKbIndexingScheduler sempre que version diverge de indexed_version (comparação '
    'simples, dispensa acompanhar updated_at). version incrementa a cada edição (versionamento '
    'simples, sem histórico completo — suficiente para o operador saber que o conteúdo mudou '
    'desde a última indexação); indexed_version=0 no INSERT garante que todo artigo novo é '
    'pego pela primeira passada do scheduler.';

CREATE TABLE cc_kb_external_sources (
    id                  BIGSERIAL PRIMARY KEY,
    url                 VARCHAR(500) NOT NULL,
    active              BOOLEAN NOT NULL DEFAULT TRUE,
    last_fetched_at     TIMESTAMPTZ,
    last_fetch_success  BOOLEAN,
    last_fetch_error    VARCHAR(300),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE cc_kb_external_sources IS
    'Fonte externa por URL (D22b — opção A escolhida): buscada e indexada periodicamente por '
    'CallCenterKbIndexingScheduler, nunca consultada ao vivo no hot-path do chat. Falha de busca '
    'nunca invalida o índice anterior (mesma disciplina do AiModelPricingSyncScheduler) — '
    'last_fetch_success/last_fetch_error só registram o resultado da última tentativa, os chunks '
    'já indexados permanecem.';

CREATE TABLE cc_kb_chunks (
    id           BIGSERIAL PRIMARY KEY,
    article_id   BIGINT REFERENCES cc_kb_articles(id) ON DELETE CASCADE,
    source_id    BIGINT REFERENCES cc_kb_external_sources(id) ON DELETE CASCADE,
    chunk_index  INTEGER NOT NULL,
    chunk_text   TEXT NOT NULL,
    embedding    vector(384) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_cc_kb_chunks_one_owner CHECK (
        (article_id IS NOT NULL AND source_id IS NULL) OR (article_id IS NULL AND source_id IS NOT NULL)
    )
);

COMMENT ON TABLE cc_kb_chunks IS
    'Trecho indexado (de um artigo próprio ou de uma fonte externa, nunca os dois) com seu '
    'embedding — reindexação de um artigo/fonte apaga e recria todos os chunks daquele dono '
    '(nunca faz UPDATE incremental, mais simples e sem risco de chunk órfão de uma versão antiga '
    'do texto). embedding vector(384): modelo local multilingue leve (ver nota no topo do arquivo).';

-- HNSW (não exige dado de treino como IVFFlat) — adequado ao volume desta VPS de dev/homologação.
CREATE INDEX idx_cc_kb_chunks_embedding ON cc_kb_chunks USING hnsw (embedding vector_cosine_ops);

CREATE TABLE cc_kb_answer_log (
    id            BIGSERIAL PRIMARY KEY,
    session_id    BIGINT NOT NULL REFERENCES cc_chat_sessions(id),
    question      TEXT NOT NULL,
    answer        TEXT,
    matched       BOOLEAN NOT NULL,
    model         VARCHAR(60),
    input_tokens  INTEGER,
    output_tokens INTEGER,
    cost_usd      NUMERIC(10,6) NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE cc_kb_answer_log IS
    'Registro de cada pergunta respondida (ou escalada) pelo nó consultar_base — matched=false '
    'significa que nenhum trecho relevante foi encontrado acima do limiar (escalou para fila '
    'humana, cost_usd=0, nenhuma chamada ao LLM); matched=true sempre teve uma chamada real ao '
    'Gemini e cost_usd correspondente. Alimenta o alerta de gasto callcenter_autosservico do '
    'Financeiro e a taxa de contenção do bot (§7 do plano-mãe).';

-- Fase 25 (§5.1 obrigatório — toda frente de IA nova aparece no Financeiro): frente
-- callcenter_autosservico soma-se a ura/insights/envios/callcenter/callcenter_nps.
-- scope era VARCHAR(20) desde a V42 — "callcenter_autosservico" tem 23 caracteres, não coube
-- (achado só na aplicação real da migration em produção — nenhum scope anterior passava de 14).
ALTER TABLE financeiro_cost_alerts ALTER COLUMN scope TYPE VARCHAR(40);
ALTER TABLE financeiro_cost_alerts DROP CONSTRAINT chk_financeiro_cost_alerts_scope;
ALTER TABLE financeiro_cost_alerts
    ADD CONSTRAINT chk_financeiro_cost_alerts_scope
        CHECK (scope IN ('ura', 'insights', 'envios', 'callcenter', 'callcenter_nps', 'callcenter_autosservico'));

INSERT INTO financeiro_cost_alerts (scope, threshold_usd, enabled) VALUES ('callcenter_autosservico', 0, FALSE);
