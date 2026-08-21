-- V94 — Correções da auditoria de banco/performance/infra de 2026-08-20.
--
-- 1) CHECK constraints em colunas com formato de enum introduzidas pela V85, sem validação de
--    valor no banco até agora (só a camada Java garante hoje). Valores confirmados por grep
--    contra o código real: IdentitySource.java (NETWORK_LOGIN|URA_INPUT|ANI — UNRESOLVED nunca é
--    persistido, ver comentário da própria classe) e CcIdentityResolutionLog.java
--    (channel: voice|chat; outcome: resolved|unresolved|rejected).
ALTER TABLE cc_interactions
    ADD CONSTRAINT chk_cc_interactions_identity_source
        CHECK (identity_source IS NULL OR identity_source IN ('NETWORK_LOGIN', 'URA_INPUT', 'ANI'));

ALTER TABLE cc_chat_sessions
    ADD CONSTRAINT chk_cc_chat_sessions_identity_source
        CHECK (identity_source IS NULL OR identity_source IN ('NETWORK_LOGIN', 'URA_INPUT', 'ANI'));

ALTER TABLE cc_identity_resolution_log
    ADD CONSTRAINT chk_cc_identity_resolution_log_channel
        CHECK (channel IN ('voice', 'chat'));

ALTER TABLE cc_identity_resolution_log
    ADD CONSTRAINT chk_cc_identity_resolution_log_outcome
        CHECK (outcome IN ('resolved', 'unresolved', 'rejected'));

-- 2) Índice ausente para a consulta horária do scheduler de relatórios agendados
--    (CallCenterReportScheduleService varre os agendamentos ativos periodicamente).
CREATE INDEX IF NOT EXISTS idx_cc_report_schedules_active
    ON cc_report_schedules (active) WHERE active = true;
