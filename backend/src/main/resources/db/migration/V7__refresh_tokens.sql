-- V7: Tabela de refresh tokens para rotação de JWT
-- Armazena apenas o hash SHA-256 do token (o token limpo nunca é persistido)

CREATE TABLE refresh_tokens (
    id         BIGSERIAL PRIMARY KEY,
    username   VARCHAR(100)             NOT NULL,
    token_hash VARCHAR(64)              NOT NULL UNIQUE,   -- SHA-256 hex
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked    BOOLEAN                  NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_refresh_tokens_username   ON refresh_tokens(username);
CREATE INDEX idx_refresh_tokens_token_hash ON refresh_tokens(token_hash);
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens(expires_at);
