-- V61 — Fase 12 do módulo Call Center: corrige lacuna estrutural encontrada no mapeamento da
-- Fase 12 (cc_agents.user_id era apenas um Integer solto, sem unicidade em nível de banco).
-- Sem este índice, dois agentes poderiam apontar para o mesmo usuário e
-- CallCenterAgentStateService.currentAgent() (findByUserId) quebraria em runtime ao encontrar
-- mais de um resultado. FK já existia (V47__callcenter_agents.sql:8), só faltava a unicidade.
--
-- Parcial (WHERE user_id IS NOT NULL) porque múltiplos agentes sem vínculo de usuário (userId
-- null) são válidos — nem todo agente do Call Center precisa ter sido provisionado a partir de
-- um cadastro de usuário do Telecom.
CREATE UNIQUE INDEX idx_cc_agents_user_id_unique ON cc_agents(user_id) WHERE user_id IS NOT NULL;
