-- =============================================================
-- V3__users_extensions.sql
-- Tabela de usuários do sistema com ramal SIP WebRTC.
-- Cada usuário recebe um ramal único a partir de 9001.
-- =============================================================

CREATE TABLE IF NOT EXISTS app_users (
    id              SERIAL PRIMARY KEY,
    username        VARCHAR(64)  NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,           -- BCrypt hash
    display_name    VARCHAR(128) NOT NULL,
    extension       INTEGER      NOT NULL UNIQUE,    -- Ramal SIP (9001, 9002, ...)
    is_active       BOOLEAN      NOT NULL DEFAULT true,
    role            VARCHAR(32)  NOT NULL DEFAULT 'USER', -- ADMIN | USER
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Índice para busca rápida por username
CREATE INDEX IF NOT EXISTS idx_app_users_username ON app_users(username);
CREATE INDEX IF NOT EXISTS idx_app_users_extension ON app_users(extension);

-- Usuário admin padrão — senha: admin123 (BCrypt hash)
-- O hash abaixo corresponde a: admin123
INSERT INTO app_users (username, password_hash, display_name, extension, is_active, role)
VALUES (
    'admin',
    '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBpwTTyU.bSVpS',
    'Administrador',
    9001,
    true,
    'ADMIN'
)
ON CONFLICT (username) DO NOTHING;
