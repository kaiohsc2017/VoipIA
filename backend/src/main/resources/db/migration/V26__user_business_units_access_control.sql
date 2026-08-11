-- V26: controle de acesso por BU e expiração de acesso do usuário.
-- BU passa a ser obrigatória (validada em app — não em constraint de banco,
-- pois o backfill abaixo popula os usuários existentes antes da regra valer
-- para novos cadastros); usuário pode ter data de expiração de acesso (máx.
-- 60 dias, validado em app) ou acesso por tempo indeterminado.

CREATE TABLE user_business_units (
    user_id           INTEGER NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    business_unit_id  INTEGER NOT NULL REFERENCES business_units(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, business_unit_id)
);

CREATE INDEX idx_user_business_units_bu ON user_business_units(business_unit_id);

ALTER TABLE app_users
    ADD COLUMN access_expires_at      DATE,
    ADD COLUMN access_indeterminate   BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN first_login_completed  BOOLEAN NOT NULL DEFAULT FALSE;

-- Backfill dos usuários já existentes: acesso indeterminado (a exigência de
-- expiração é nova, não deve expirar retroativamente ninguém) e vinculados a
-- todas as BUs ativas (a exigência de BU obrigatória é nova, sem isso o
-- próximo login de qualquer usuário existente veria a base vazia em todo o
-- sistema). first_login_completed=TRUE evita reabrir o prompt de MFA do
-- "primeiro login" para quem já usa o sistema.
UPDATE app_users SET access_indeterminate = TRUE, first_login_completed = TRUE;

INSERT INTO user_business_units (user_id, business_unit_id)
SELECT u.id, bu.id
FROM app_users u
CROSS JOIN business_units bu
WHERE bu.is_active = TRUE;
