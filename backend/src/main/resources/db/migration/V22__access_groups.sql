-- V22: Grupos de acesso configuráveis — substitui o binário role (ADMIN|USER)
-- por grupos nomeados com permissão de leitura/escrita por menu (resource_key).
-- O catálogo de recursos (quais menus existem) fica em código (Java/TS), não
-- em tabela — os menus são fixos, só a matriz de permissões é dinâmica.
--
-- resource_key = "<sistema>.<id-do-menu>", espelhando os ids já usados no
-- Sidebar.tsx (telecom.*) e no NAV do agents-platform/frontend (agents.*).
--
-- Aditiva e reversível nesta fase: cria tabelas novas + coluna nullable.
-- A coluna legada app_users.role é mantida (dual-emit no JWT até a Fase 6
-- de limpeza, depois do período de estabilização em produção).

CREATE TABLE access_groups (
    id          SERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(255),
    is_system   BOOLEAN   NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE access_groups IS 'Grupos de acesso configuráveis (RBAC granular) — substituem o role binário ADMIN|USER';
COMMENT ON COLUMN access_groups.is_system IS 'Grupos seed (Administradores/Usuários) — não podem ser excluídos pela UI';

CREATE TABLE access_group_permissions (
    group_id     INTEGER     NOT NULL REFERENCES access_groups(id) ON DELETE CASCADE,
    resource_key VARCHAR(64) NOT NULL,
    can_read     BOOLEAN     NOT NULL DEFAULT FALSE,
    can_write    BOOLEAN     NOT NULL DEFAULT FALSE,
    PRIMARY KEY (group_id, resource_key)
);

COMMENT ON COLUMN access_group_permissions.resource_key IS 'Ex: telecom.settings, agents.secrets — catálogo fixo em código';

-- ---------------------------------------------------------------------------
-- Seed: 2 grupos de sistema reproduzindo EXATAMENTE o comportamento binário
-- atual (SecurityConfig.java + auth.py), para o cutover ser transparente.
-- ---------------------------------------------------------------------------

INSERT INTO access_groups (id, name, description, is_system) VALUES
    (1, 'Administradores', 'Acesso total de leitura e escrita a todos os módulos', TRUE),
    (2, 'Usuários',        'Acesso operacional padrão — equivalente ao antigo role USER', TRUE);
SELECT setval('access_groups_id_seq', (SELECT MAX(id) FROM access_groups));

-- Administradores: leitura + escrita em todos os 19 recursos catalogados.
INSERT INTO access_group_permissions (group_id, resource_key, can_read, can_write)
SELECT 1, key, TRUE, TRUE FROM unnest(ARRAY[
    'telecom.dashboard', 'telecom.modulo1', 'telecom.modulo2', 'telecom.modulo3',
    'telecom.agents_link', 'telecom.masterdata', 'telecom.users', 'telecom.settings',
    'telecom.logs', 'telecom.security', 'telecom.audit',
    'agents.dashboard', 'agents.agents', 'agents.servers', 'agents.knowledge',
    'agents.logs', 'agents.reports', 'agents.secrets', 'agents.llm'
]) AS key;

-- Usuários: espelha o que SecurityConfig.java/auth.py já aplicam hoje.
INSERT INTO access_group_permissions (group_id, resource_key, can_read, can_write) VALUES
    (2, 'telecom.dashboard',     TRUE,  TRUE),
    (2, 'telecom.modulo1',       TRUE,  FALSE), -- escrita de /uras/** exige ADMIN ou INTERNAL
    (2, 'telecom.modulo2',       TRUE,  TRUE),
    (2, 'telecom.modulo3',       TRUE,  TRUE),
    (2, 'telecom.agents_link',   TRUE,  FALSE), -- só link de navegação
    (2, 'telecom.masterdata',    TRUE,  TRUE),
    (2, 'telecom.users',         FALSE, FALSE), -- /users/** exige ADMIN
    (2, 'telecom.settings',      FALSE, FALSE), -- /settings/** exige ADMIN
    (2, 'telecom.logs',          FALSE, FALSE), -- /logs/** exige ADMIN
    (2, 'telecom.security',      FALSE, FALSE), -- /security/** exige ADMIN
    (2, 'telecom.audit',         TRUE,  FALSE), -- endpoint só de leitura
    (2, 'agents.dashboard',      TRUE,  TRUE),
    (2, 'agents.agents',         TRUE,  FALSE), -- criar/editar/excluir/executar exige ADMIN
    (2, 'agents.servers',        TRUE,  FALSE), -- escrita/teste exige ADMIN
    (2, 'agents.knowledge',      TRUE,  TRUE),
    (2, 'agents.logs',           FALSE, FALSE), -- executions.py GET /logs exige ADMIN (evita leak de DSN/senha em erro)
    (2, 'agents.reports',        TRUE,  FALSE), -- alertas são só leitura
    (2, 'agents.secrets',        FALSE, FALSE), -- system.py exige ADMIN em tudo
    (2, 'agents.llm',            FALSE, FALSE); -- GET /config já exige ADMIN

-- ---------------------------------------------------------------------------
-- Vincula os usuários existentes ao grupo equivalente ao seu role atual.
-- ---------------------------------------------------------------------------

ALTER TABLE app_users ADD COLUMN access_group_id INTEGER REFERENCES access_groups(id);

UPDATE app_users SET access_group_id = 1 WHERE role = 'ADMIN';
UPDATE app_users SET access_group_id = 2 WHERE role = 'USER' OR access_group_id IS NULL;

ALTER TABLE app_users ALTER COLUMN access_group_id SET NOT NULL;

CREATE INDEX idx_app_users_access_group_id ON app_users(access_group_id);
