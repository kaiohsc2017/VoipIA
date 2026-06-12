-- V4__settings_history.sql
-- Histórico de alterações do arquivo .env via interface web (Fase 12)

CREATE TABLE IF NOT EXISTS settings_history (
    id           BIGSERIAL    PRIMARY KEY,
    changed_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    changed_by   VARCHAR(120) NOT NULL DEFAULT 'admin',
    env_key      VARCHAR(255) NOT NULL,
    old_value    TEXT,
    new_value    TEXT,
    ip_address   VARCHAR(64)
);

-- Índice para busca por data (consultas recentes primeiro)
CREATE INDEX IF NOT EXISTS idx_settings_history_changed_at
    ON settings_history (changed_at DESC);

-- Índice para busca por chave
CREATE INDEX IF NOT EXISTS idx_settings_history_key
    ON settings_history (env_key);

COMMENT ON TABLE settings_history IS 'Auditoria de todas as alterações realizadas via módulo de Configurações';
COMMENT ON COLUMN settings_history.env_key   IS 'Nome da variável de ambiente alterada';
COMMENT ON COLUMN settings_history.old_value IS 'Valor anterior (NULL em criação). Valores secretos armazenados mascarados.';
COMMENT ON COLUMN settings_history.new_value IS 'Novo valor. Valores secretos armazenados mascarados.';
