-- V37: Insights vira SPA independente (/insights) — RBAC granular por aba.
--
-- Antes: um único resource_key "telecom.insights" cobria menu + todos os
-- dados (Chamadas/Dashboard/Processamento/Custos). Nenhuma migration seed
-- (V22 é anterior à feature Insights) chegou a inserir esse resource_key nos
-- grupos "Administradores"/"Usuários" — ADMIN sempre teve acesso via
-- ROLE_ADMIN (não depende de linha na tabela); grupos customizados só têm
-- acesso hoje se algum admin concedeu manualmente pela UI (AccessGroups.tsx).
--
-- Depois (espelhando o namespace agents.*): "telecom.insights_link" é só o
-- item de menu no Telecom que abre a SPA via iframe; "insights.calls",
-- "insights.dashboard", "insights.processing", "insights.costs" são as
-- permissões granulares por aba, verificadas pelo backend (SecurityConfig)
-- e pela SPA (App.tsx) — ver ResourceCatalog.java.
--
-- Migra QUALQUER concessão manual pré-existente de "telecom.insights" para os
-- novos resources (preserva acesso de grupos customizados) antes de remover
-- o resource_key antigo.

INSERT INTO access_group_permissions (group_id, resource_key, can_read, can_write)
SELECT group_id, 'telecom.insights_link', can_read, FALSE
FROM access_group_permissions
WHERE resource_key = 'telecom.insights'
ON CONFLICT (group_id, resource_key) DO UPDATE
    SET can_read = GREATEST(access_group_permissions.can_read, EXCLUDED.can_read);

INSERT INTO access_group_permissions (group_id, resource_key, can_read, can_write)
SELECT group_id, new_key, can_read, can_write
FROM access_group_permissions
CROSS JOIN unnest(ARRAY['insights.calls', 'insights.dashboard', 'insights.processing', 'insights.costs']) AS new_key
WHERE resource_key = 'telecom.insights'
ON CONFLICT (group_id, resource_key) DO UPDATE
    SET can_read  = GREATEST(access_group_permissions.can_read, EXCLUDED.can_read),
        can_write = GREATEST(access_group_permissions.can_write, EXCLUDED.can_write);

DELETE FROM access_group_permissions WHERE resource_key = 'telecom.insights';
