-- V47 — Agentes e ramais do Call Center (Fase 2). Metadado próprio do VoipIA, distinto das
-- tabelas ARA (V46): aqui vive o que o Asterisk não sabe (BU, vínculo com app_users, senha gerada
-- pra exibição sob demanda, motivos de pausa). O provisionamento (domain/callcenter) escreve nas
-- duas — aqui e em ps_endpoints/ps_auths/ps_aors.

CREATE TABLE cc_agents (
    id                 BIGSERIAL PRIMARY KEY,
    user_id            INTEGER REFERENCES app_users(id),
    name               VARCHAR(150) NOT NULL,
    business_unit_id   INTEGER REFERENCES business_units(id),
    active             BOOLEAN NOT NULL DEFAULT true,
    created_at         TIMESTAMP NOT NULL DEFAULT now(),
    updated_at         TIMESTAMP NOT NULL DEFAULT now()
);

-- Metadado do ramal ARA provisionado para o agente — 1:1 com cc_agents. A senha fica aqui em
-- texto plano (mesmo padrão já usado pelas senhas de ramal do .env/RAMAL_*_PASSWORD neste
-- projeto) — nunca é devolvida em GET normal, só em endpoint dedicado sob demanda.
CREATE TABLE cc_extensions (
    id           BIGSERIAL PRIMARY KEY,
    agent_id     BIGINT NOT NULL UNIQUE REFERENCES cc_agents(id) ON DELETE CASCADE,
    extension    VARCHAR(20) NOT NULL UNIQUE,
    secret       VARCHAR(64) NOT NULL,
    created_at   TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE cc_pause_reasons (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(30) NOT NULL UNIQUE,
    label       VARCHAR(100) NOT NULL,
    productive  BOOLEAN NOT NULL DEFAULT false,
    active      BOOLEAN NOT NULL DEFAULT true
);

INSERT INTO cc_pause_reasons (code, label, productive) VALUES
    ('ALMOCO', 'Almoço', false),
    ('BANHEIRO', 'Banheiro', false),
    ('FEEDBACK', 'Feedback com liderança', true),
    ('TREINAMENTO', 'Treinamento', true);
