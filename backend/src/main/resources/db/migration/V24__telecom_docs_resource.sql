-- V24: novo resource_key "telecom.docs" para a página de Documentação
-- (migrada de agents-platform/frontend/docs.html para o Telecom e expandida
-- com conteúdo do próprio sistema Telecom).
--
-- Liberado por padrão para os dois grupos seed (Administradores e Usuários),
-- reproduzindo a decisão de que qualquer usuário autenticado pode consultar a
-- documentação. Não há ação de escrita associada a este recurso (conteúdo
-- estático embutido no bundle do frontend) — can_write=TRUE para
-- Administradores segue apenas o padrão da V22 (grupo 1 = leitura+escrita em
-- todo o catálogo), sem efeito prático.

INSERT INTO access_group_permissions (group_id, resource_key, can_read, can_write) VALUES
    (1, 'telecom.docs', TRUE, TRUE),
    (2, 'telecom.docs', TRUE, FALSE);
