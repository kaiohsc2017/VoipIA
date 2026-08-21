-- V96 — Correção definitiva do achado de RBAC via ILIKE documentado na V95.
--
-- A V91 concedeu callcenter.wfm/callcenter.copilot/insights.semantic_search/admin.sso a
-- qualquer grupo cujo NOME batesse em ILIKE '%admin%'/'%supervis%'/'%agent%'/'%atend%'/
-- '%qualidade%' — um INSERT ... SELECT executado uma única vez, no momento em que a V91 foi
-- aplicada (Flyway não re-executa migrations já aplicadas). Não existe, em nenhum lugar do
-- código Java, uma rotina que reaplique esse casamento por nome na criação de um grupo novo
-- (confirmado: nenhuma ocorrência de ILIKE/pattern matching em AccessGroupService ou em
-- qualquer outro ponto de criação de grupo) — ou seja, o mecanismo da V91 não é "vivo": ele já
-- rodou e não roda de novo. O achado documentado na V95 tratava esse casamento por nome como
-- risco prospectivo (uma migration futura hipotética repetir o padrão), não como um gatilho
-- ativo hoje.
--
-- Ainda assim, a V91 deixou a concessão real acoplada a uma condição de nome livre em vez de
-- uma referência estável — esta migration corrige isso substituindo a intenção por uma
-- referência EXPLÍCITA e fixa ao id do grupo Administradores (id=1, seed fixo desde a V1, já
-- usado com a mesma suposição em CLAUDE.md e no comentário da própria V95).
--
-- Confirmado em produção antes de escrever este SQL (somente leitura): hoje só o grupo
-- Administradores (id=1) tem essas 4 concessões — a revogação abaixo é um no-op no estado atual,
-- e serve como rede de segurança caso algum dado tenha migrado de forma inesperada.

-- 1) Rede de segurança: remove qualquer concessão dessas 4 permissões que não seja a do grupo
--    Administradores (id=1) — hoje um no-op confirmado, mas evita que uma concessão feita por
--    engano (ou por uma reversão manual de dado) sobreviva silenciosamente.
DELETE FROM access_group_permissions
WHERE resource_key IN ('callcenter.wfm', 'callcenter.copilot', 'insights.semantic_search', 'admin.sso')
  AND group_id <> 1;

-- 2) Garante a concessão real, pelo id fixo do grupo Administradores — nunca mais por nome.
INSERT INTO access_group_permissions (group_id, resource_key, can_read, can_write)
VALUES
    (1, 'callcenter.wfm', true, true),
    (1, 'callcenter.copilot', true, true),
    (1, 'insights.semantic_search', true, true),
    (1, 'admin.sso', true, true)
ON CONFLICT (group_id, resource_key) DO UPDATE
    SET can_read = true, can_write = true;
