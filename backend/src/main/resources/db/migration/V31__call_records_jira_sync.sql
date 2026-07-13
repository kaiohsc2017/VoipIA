-- V31: sync de status/resolução do Jira de volta para call_records — habilita
-- a aba "Ranking de Atendimentos" (URA) a mostrar as soluções mais aplicadas.
-- Até aqui jira_issue_status era gravado uma única vez ("Aberto") na criação
-- do chamado e nunca mais atualizado; o novo JiraSyncScheduler passa a manter
-- jira_issue_status e jira_resolution em dia via polling periódico.

ALTER TABLE call_records ADD COLUMN jira_resolution VARCHAR(100);
ALTER TABLE call_records ADD COLUMN jira_last_synced_at TIMESTAMP;
