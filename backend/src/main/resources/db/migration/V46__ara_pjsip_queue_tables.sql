-- V46 — Tabelas Realtime (ARA) do Asterisk para o módulo Call Center, Fase 2.
-- Nomes de tabela e de coluna são ditados pelo próprio Asterisk (sorcery.conf/extconfig.conf
-- apontam para elas) — não são escolha nossa. Schema reduzido às colunas que o provisionamento
-- de ramal/fila do domain/callcenter realmente escreve (mesmo padrão dos ramais estáticos
-- 1001/1002/9002 em pjsip.conf.template — sem WebRTC; isso fica para a Fase 4/Desktop do Agente,
-- quando entrará uma migration nova só com as colunas WebRTC que faltarem).
--
-- Todas as colunas de texto usam VARCHAR simples (não os ENUMs nativos do schema oficial do
-- Asterisk) — o valor é interpretado pelo Asterisk como string de configuração de qualquer forma;
-- ENUM nativo do Postgres só adicionaria rigidez sem benefício real aqui.

CREATE TABLE ps_endpoints (
    id               VARCHAR(40) PRIMARY KEY,
    transport        VARCHAR(40),
    aors             VARCHAR(200),
    auth             VARCHAR(40),
    context          VARCHAR(40),
    disallow         VARCHAR(200),
    allow            VARCHAR(200),
    direct_media     VARCHAR(3),
    force_rport      VARCHAR(3),
    rewrite_contact  VARCHAR(3),
    callerid         VARCHAR(40),
    identify_by      VARCHAR(40)
);

CREATE TABLE ps_auths (
    id         VARCHAR(40) PRIMARY KEY,
    auth_type  VARCHAR(10),
    password   VARCHAR(80),
    realm      VARCHAR(40),
    username   VARCHAR(40)
);

CREATE TABLE ps_aors (
    id                 VARCHAR(40) PRIMARY KEY,
    contact            VARCHAR(255),
    max_contacts       INTEGER,
    qualify_frequency  INTEGER,
    remove_existing    VARCHAR(3)
);

CREATE TABLE queues (
    name          VARCHAR(128) PRIMARY KEY,
    context       VARCHAR(128),
    strategy      VARCHAR(20),
    timeout       INTEGER,
    maxlen        INTEGER,
    musiconhold   VARCHAR(128),
    wrapuptime    INTEGER
);

CREATE TABLE queue_members (
    uniqueid        SERIAL PRIMARY KEY,
    queue_name      VARCHAR(80) NOT NULL,
    interface       VARCHAR(80) NOT NULL,
    membername      VARCHAR(80),
    state_interface VARCHAR(80),
    penalty         INTEGER DEFAULT 0,
    paused          INTEGER DEFAULT 0,
    UNIQUE (queue_name, interface)
);
