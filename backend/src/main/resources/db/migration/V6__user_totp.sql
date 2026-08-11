-- V6__user_totp.sql
-- Adiciona campos de 2FA TOTP na tabela app_users (Fase 13)

ALTER TABLE app_users
    ADD COLUMN IF NOT EXISTS totp_secret  VARCHAR(64),
    ADD COLUMN IF NOT EXISTS totp_enabled BOOLEAN NOT NULL DEFAULT FALSE;
