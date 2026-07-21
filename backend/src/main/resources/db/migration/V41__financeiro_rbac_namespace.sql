-- V41: Módulo Financeiro — centraliza as telas de Custo de IA (URA/Insights/Análise Sob
-- Demanda) sob um namespace granular próprio (financeiro.*), espelhando o padrão de V37.
--
-- Antes: custo de URA vivia sob "telecom.modulo1" (aba dentro do Módulo URA); custo de
-- Insights (Verint) vivia sob "insights.costs" (aba própria, sem outro uso); custo de
-- Análise Sob Demanda (uploads do supervisor) vivia sob "insights.uploads"
-- (compartilhado com o resto do portal de upload).
--
-- Depois: "financeiro.ura" / "financeiro.insights" / "financeiro.envios" controlam as 2
-- telas de custo (lista + dashboard) de cada frente, agora centralizadas no módulo
-- Financeiro (ver ResourceCatalog.java). "telecom.modulo1" e "insights.uploads" continuam
-- existindo — seguem protegendo o restante de suas telas (chamadas/uras/ranking; upload e
-- listagem de lotes) — só deixam de proteger as rotas /costs/**. "insights.costs" não
-- protegia mais nada além de custo — é removido do catálogo e da tabela abaixo.
--
-- Migra QUALQUER concessão manual pré-existente antes de remover o resource_key órfão
-- (mesma lógica de preservação de acesso de V37).

INSERT INTO access_group_permissions (group_id, resource_key, can_read, can_write)
SELECT group_id, 'financeiro.ura', can_read, can_write
FROM access_group_permissions
WHERE resource_key = 'telecom.modulo1'
ON CONFLICT (group_id, resource_key) DO UPDATE
    SET can_read  = GREATEST(access_group_permissions.can_read, EXCLUDED.can_read),
        can_write = GREATEST(access_group_permissions.can_write, EXCLUDED.can_write);

INSERT INTO access_group_permissions (group_id, resource_key, can_read, can_write)
SELECT group_id, 'financeiro.insights', can_read, can_write
FROM access_group_permissions
WHERE resource_key = 'insights.costs'
ON CONFLICT (group_id, resource_key) DO UPDATE
    SET can_read  = GREATEST(access_group_permissions.can_read, EXCLUDED.can_read),
        can_write = GREATEST(access_group_permissions.can_write, EXCLUDED.can_write);

INSERT INTO access_group_permissions (group_id, resource_key, can_read, can_write)
SELECT group_id, 'financeiro.envios', can_read, can_write
FROM access_group_permissions
WHERE resource_key = 'insights.uploads'
ON CONFLICT (group_id, resource_key) DO UPDATE
    SET can_read  = GREATEST(access_group_permissions.can_read, EXCLUDED.can_read),
        can_write = GREATEST(access_group_permissions.can_write, EXCLUDED.can_write);

DELETE FROM access_group_permissions WHERE resource_key = 'insights.costs';
