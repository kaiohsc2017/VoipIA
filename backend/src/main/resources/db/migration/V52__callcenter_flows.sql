-- V52 — Flow builder visual, canal voz (Fase 5, sub-fase 5a). cc_flows guarda o metadado do
-- fluxo (nome, canal, ramal de entrada na faixa 6000-6999, exclusiva do motor ARI/Stasis — nunca
-- sobrepõe as URAs legadas em 2XXX nem as filas em 5XXX) e o ponteiro para a versão publicada.
-- cc_flow_versions guarda o grafo (nodes/edges do React Flow) versionado: só uma DRAFT por fluxo,
-- publicar arquiva a PUBLISHED anterior e cria uma nova DRAFT — a versão publicada nunca sofre
-- UPDATE no grafo, para que uma chamada em curso nunca mude de comportamento no meio (rollback
-- também nunca edita o grafo, só troca o ponteiro published_version_id). Nesta sub-fase o motor de
-- execução ainda não existe (Fase 5b) — a publicação já fica bloqueada em código
-- (FlowGraphValidator) para qualquer fluxo que use nó ainda não implementado.

CREATE TABLE cc_flows (
    id                     BIGSERIAL PRIMARY KEY,
    name                   VARCHAR(150) NOT NULL,
    description            TEXT,
    channel                VARCHAR(10) NOT NULL DEFAULT 'voice',
    entry_extension        VARCHAR(20),
    business_unit_id       INTEGER REFERENCES business_units(id),
    active                 BOOLEAN NOT NULL DEFAULT true,
    -- Sem FK para cc_flow_versions aqui (ela só é criada a seguir) — a integridade é garantida
    -- pela aplicação (CallCenterFlowService), nunca por escrita direta em SQL.
    published_version_id   BIGINT,
    created_by             VARCHAR(100),
    updated_by              VARCHAR(100),
    created_at             TIMESTAMP NOT NULL DEFAULT now(),
    updated_at             TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_cc_flows_name UNIQUE (name),
    CONSTRAINT uq_cc_flows_entry_extension UNIQUE (entry_extension)
);

CREATE TABLE cc_flow_versions (
    id                  BIGSERIAL PRIMARY KEY,
    flow_id             BIGINT NOT NULL REFERENCES cc_flows(id) ON DELETE CASCADE,
    version_number      INTEGER NOT NULL,
    status              VARCHAR(15) NOT NULL,
    graph               JSONB NOT NULL,
    notes               TEXT,
    published_at        TIMESTAMP,
    published_by        VARCHAR(100),
    created_at          TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_cc_flow_versions_flow_version UNIQUE (flow_id, version_number)
);
CREATE INDEX idx_cc_flow_versions_flow_status ON cc_flow_versions(flow_id, status);

-- Backstop de banco contra corrida em publish()/rollback(): mesmo que dois requests concorrentes
-- leiam "sem PUBLISHED ainda" antes de qualquer commit, o segundo INSERT/UPDATE que tentar deixar
-- duas linhas PUBLISHED para o mesmo fluxo falha aqui. O lock pessimista em CallCenterFlowService
-- evita a corrida na prática; este índice garante a invariante mesmo se o lock falhar.
CREATE UNIQUE INDEX uq_cc_flow_versions_one_published_per_flow
    ON cc_flow_versions(flow_id) WHERE status = 'PUBLISHED';
