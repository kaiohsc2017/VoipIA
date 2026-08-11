-- V33: preenche expected_values das perguntas de "tipo de atendimento" que ainda
-- estavam sem valor configurado — sem isso, o ai-agent (jira_call_flow.py) aceitava
-- qualquer texto vindo do STT como categoria válida (call_type), poluindo os
-- indicadores da aba Ranking de Atendimentos com respostas malformadas/ruído
-- (ex: "chei", "Cheguei.", "[No discernable speech...]").
--
-- Restrito às perguntas cujo jira_field_key indica claramente o campo de tipo de
-- ticket, para não sobrescrever nenhuma configuração customizada já feita pelo
-- administrador em outros campos.

UPDATE ura_questions
SET expected_values = 'Incidente,Requisição'
WHERE (jira_field_key ILIKE '%tipo%' OR jira_field_key ILIKE '%issuetype%' OR jira_field_key ILIKE '%type_ticket%')
  AND (expected_values IS NULL OR expected_values = '');
