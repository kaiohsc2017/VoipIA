-- V84 — Espelha o identificador de matrícula do AD (employeeID) em ad_users.
-- Fecha uma das 3 lacunas reais da Fase 1 do Call Center: sem employee_id, a Fase 14
-- (screen pop) não tem um identificador estável para correlacionar cc_agents ao AD além do
-- sam_account_name (frágil se o AD renomear o login). Nullable — nem todo AD popula esse
-- atributo; o sync noturno preenche organicamente quando existir, sem backfill manual.

ALTER TABLE ad_users ADD COLUMN employee_id VARCHAR(64);

CREATE INDEX idx_ad_users_employee_id ON ad_users(employee_id);
