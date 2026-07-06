-- V28: novo resource_key "telecom.release" para a página de Release Notes
-- (changelog estático do sistema, exibido abaixo de Documentação no menu).
--
-- Liberado por padrão para os dois grupos seed (Administradores e Usuários),
-- mesmo padrão da V24 (telecom.docs): não há ação de escrita associada a este
-- recurso (changelog estático embutido no bundle do frontend) — can_write=TRUE
-- para Administradores segue apenas a convenção da V22 (grupo 1 = leitura+
-- escrita em todo o catálogo), sem efeito prático.

INSERT INTO access_group_permissions (group_id, resource_key, can_read, can_write) VALUES
    (1, 'telecom.release', TRUE, TRUE),
    (2, 'telecom.release', TRUE, FALSE);
