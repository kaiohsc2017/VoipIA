-- V76 — Roteamento por skill do Call Center (Fase 5f.1 do plano
-- callcenter-fases-5-7-9.plan.md).
--
-- Divergência do plano (documentada aqui de propósito, ver relatório da fatia): o texto do plano
-- descreve "cc_agent_skill"/"cc_queue_skill" (singular) como já existentes desde a V48 e diz que
-- esta migration "acrescenta level/min_level". Na V48 real os nomes são PLURAL
-- (cc_agent_skills/cc_queue_skills) e NENHUMA das duas colunas de nível existia ainda — é esta
-- migration que as cria pela primeira vez, via ALTER TABLE (aditivo, sem perda de dado: ambas as
-- tabelas nascem sem linha alguma hoje, confirmado antes de escrever este SQL).
--
-- Escala de nível escolhida (1 a 5, igual para agente e para exigência de fila):
--   1 = iniciante/em treinamento   2 = básico   3 = intermediário   4 = avançado   5 = especialista
-- DEFAULT 1 preserva o comportamento atual quando a linha já existir sem nível definido
-- (nenhuma linha existe hoje nas duas tabelas, então o default só importa para inserts futuros).
--
-- Regra de precedência skill × prioridade manual (Fase 12), decidida nesta fatia:
--   skill decide SOMENTE elegibilidade (é membro ou não da fila) — nunca escreve em
--   cc_queue_members.penalty. A prioridade manual do supervisor (penalty) é e continua sendo
--   100% a fonte de verdade, sempre; o motor de skill nunca a lê nem a sobrescreve, em nenhuma
--   circunstância. Por isso esta migration NÃO adiciona nenhuma coluna em cc_queue_members —
--   não há necessidade de um flag de precedência porque penalty simplesmente nunca é tocado por
--   este motor. O recálculo de participação (adicionar/remover cc_queue_members conforme skill)
--   só roda quando o operador aciona explicitamente o endpoint de recálculo (nunca em job de
--   background silencioso) — ver CallCenterSkillRoutingService.recalculateQueueMembership.

ALTER TABLE cc_agent_skills
    ADD COLUMN level INTEGER NOT NULL DEFAULT 1;

ALTER TABLE cc_agent_skills
    ADD CONSTRAINT cc_agent_skills_level_range CHECK (level BETWEEN 1 AND 5);

ALTER TABLE cc_queue_skills
    ADD COLUMN min_level INTEGER NOT NULL DEFAULT 1;

ALTER TABLE cc_queue_skills
    ADD CONSTRAINT cc_queue_skills_min_level_range CHECK (min_level BETWEEN 1 AND 5);
