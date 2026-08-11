-- V32: coluna de assunto classificado por IA (Módulo 1 — indicador "mais pedido"
-- na aba Ranking de Atendimentos). Preenchida de forma best-effort pelo ai-agent
-- logo após o fim da chamada — pode ficar NULL se a classificação falhar.

ALTER TABLE call_records ADD COLUMN subject_tag VARCHAR(100);
