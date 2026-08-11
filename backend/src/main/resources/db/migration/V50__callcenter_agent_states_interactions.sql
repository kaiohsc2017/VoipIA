-- V50 — Estados do agente e interações (Fase 4). cc_agent_states rastreia a linha do tempo de
-- estado/pausa de cada agente (base de ocupação/aderência/ACW); cc_interactions formaliza o
-- vínculo fila/agente/gravação anunciado no comentário de V49 ("cc_interactions chega na
-- Fase 4"); cc_interaction_events guarda o traço bruto de eventos AMI por interação. Sem
-- particionamento ainda — fica para a Fase 10, quando o volume real justificar.

CREATE TABLE cc_agent_states (
    id               BIGSERIAL PRIMARY KEY,
    agent_id         BIGINT NOT NULL REFERENCES cc_agents(id) ON DELETE CASCADE,
    state            VARCHAR(20) NOT NULL,
    pause_reason_id  BIGINT REFERENCES cc_pause_reasons(id),
    started_at       TIMESTAMP NOT NULL DEFAULT now(),
    ended_at         TIMESTAMP
);
-- Índice parcial: a consulta mais frequente é "qual o estado ABERTO (ended_at IS NULL) de um
-- agente" — evita varrer todo o histórico de estados a cada troca de tela.
CREATE INDEX idx_cc_agent_states_agent_open ON cc_agent_states(agent_id) WHERE ended_at IS NULL;

CREATE TABLE cc_dispositions (
    id      BIGSERIAL PRIMARY KEY,
    code    VARCHAR(30) NOT NULL UNIQUE,
    label   VARCHAR(100) NOT NULL,
    active  BOOLEAN NOT NULL DEFAULT true
);
INSERT INTO cc_dispositions (code, label) VALUES
    ('RESOLVIDO', 'Resolvido'),
    ('TRANSFERIDO', 'Transferido'),
    ('SEM_SOLUCAO', 'Sem solução'),
    ('ENGANO', 'Ligação enganada'),
    ('ABANDONO', 'Abandono pelo cliente');

CREATE TABLE cc_interactions (
    id                BIGSERIAL PRIMARY KEY,
    queue_id          BIGINT REFERENCES cc_queues(id),
    agent_id          BIGINT REFERENCES cc_agents(id),
    channel_uniqueid  VARCHAR(64) NOT NULL UNIQUE,
    ani               VARCHAR(30),
    business_unit_id  INTEGER REFERENCES business_units(id),
    queued_at         TIMESTAMP NOT NULL DEFAULT now(),
    answered_at       TIMESTAMP,
    ended_at          TIMESTAMP,
    disposition_id    BIGINT REFERENCES cc_dispositions(id),
    created_at        TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_cc_interactions_agent_open ON cc_interactions(agent_id) WHERE ended_at IS NULL;
CREATE INDEX idx_cc_interactions_queue_id ON cc_interactions(queue_id);

CREATE TABLE cc_interaction_events (
    id             BIGSERIAL PRIMARY KEY,
    interaction_id BIGINT NOT NULL REFERENCES cc_interactions(id) ON DELETE CASCADE,
    event_type     VARCHAR(40) NOT NULL,
    details        TEXT,
    occurred_at    TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_cc_interaction_events_interaction_id ON cc_interaction_events(interaction_id);

-- Liga a gravação (V49) à interação formal, agora que ela existe.
ALTER TABLE cc_recordings ADD COLUMN interaction_id BIGINT REFERENCES cc_interactions(id);
