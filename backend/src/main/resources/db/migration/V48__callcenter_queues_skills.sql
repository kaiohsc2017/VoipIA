-- V48 — Filas e skills do Call Center (Fase 2). cc_queues/cc_queue_members são metadado nosso
-- (BU, auditoria, skills) — distintos das tabelas ARA `queues`/`queue_members` (V46), que só
-- guardam o que o Asterisk lê. O provisionamento escreve nas duas ao criar/editar uma fila.

CREATE TABLE cc_queues (
    id                BIGSERIAL PRIMARY KEY,
    name              VARCHAR(20) NOT NULL UNIQUE,
    display_name      VARCHAR(150) NOT NULL,
    business_unit_id  INTEGER REFERENCES business_units(id),
    strategy          VARCHAR(20) NOT NULL DEFAULT 'ringall',
    timeout_seconds   INTEGER NOT NULL DEFAULT 15,
    active            BOOLEAN NOT NULL DEFAULT true,
    created_at        TIMESTAMP NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE cc_queue_members (
    id          BIGSERIAL PRIMARY KEY,
    queue_id    BIGINT NOT NULL REFERENCES cc_queues(id) ON DELETE CASCADE,
    agent_id    BIGINT NOT NULL REFERENCES cc_agents(id) ON DELETE CASCADE,
    penalty     INTEGER NOT NULL DEFAULT 0,
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (queue_id, agent_id)
);

CREATE TABLE cc_skills (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(100) NOT NULL UNIQUE,
    description  VARCHAR(255)
);

CREATE TABLE cc_agent_skills (
    agent_id  BIGINT NOT NULL REFERENCES cc_agents(id) ON DELETE CASCADE,
    skill_id  BIGINT NOT NULL REFERENCES cc_skills(id) ON DELETE CASCADE,
    PRIMARY KEY (agent_id, skill_id)
);

CREATE TABLE cc_queue_skills (
    queue_id  BIGINT NOT NULL REFERENCES cc_queues(id) ON DELETE CASCADE,
    skill_id  BIGINT NOT NULL REFERENCES cc_skills(id) ON DELETE CASCADE,
    PRIMARY KEY (queue_id, skill_id)
);
