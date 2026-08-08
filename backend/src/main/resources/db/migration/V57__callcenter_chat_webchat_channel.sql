-- V57 — Fase 7b do módulo Call Center: canal fixo usado pelo widget público de chat
-- (o simulador de cliente da Fase 7a usa 'internal_test'; o widget real usa 'webchat').
-- ON CONFLICT DO NOTHING mantém a migration idempotente contra reaplicação acidental.

INSERT INTO cc_chat_channels (code, display_name, active)
VALUES ('webchat', 'Widget público do site', true)
ON CONFLICT (code) DO NOTHING;
