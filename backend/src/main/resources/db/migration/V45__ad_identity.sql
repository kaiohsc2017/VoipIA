-- V45 — Identidade / Active Directory (módulo Call Center, Fase 1)
-- Espelho local dos atributos consultáveis do AD (login bind ao vivo, tela lê sempre daqui —
-- resiliente a AD fora do ar), auditoria de sincronização e mapeamento opcional grupo AD → grupo
-- de acesso local.

CREATE TABLE ad_users (
    id                BIGSERIAL PRIMARY KEY,
    sam_account_name  VARCHAR(128) NOT NULL UNIQUE,
    display_name      VARCHAR(255),
    department        VARCHAR(255),
    office             VARCHAR(255),
    title             VARCHAR(255),
    member_of         TEXT,
    manager_sam       VARCHAR(128),
    email             VARCHAR(255),
    telephone_number  VARCHAR(64),
    last_synced_at    TIMESTAMP,
    created_at        TIMESTAMP NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE ad_sync_runs (
    id             BIGSERIAL PRIMARY KEY,
    started_at     TIMESTAMP NOT NULL,
    finished_at    TIMESTAMP,
    status         VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    users_synced   INTEGER NOT NULL DEFAULT 0,
    error_message  TEXT
);

CREATE TABLE ad_group_mappings (
    id              BIGSERIAL PRIMARY KEY,
    ad_group_name   VARCHAR(255) NOT NULL UNIQUE,
    access_group_id INTEGER NOT NULL REFERENCES access_groups(id),
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_ad_users_sam_account_name ON ad_users(sam_account_name);

-- Marca um app_user como provisionado/vinculado ao AD — só uma conta com ad_linked=true pode
-- autenticar via bind AD (AuthController). Sem isso, uma conta local pré-existente com o mesmo
-- username de uma conta do AD seria autenticável por qualquer um que soubesse a senha AD daquele
-- username, contornando a senha local (achado CRITICAL da revisão de segurança da Fase 1).
ALTER TABLE app_users ADD COLUMN ad_linked BOOLEAN NOT NULL DEFAULT false;
