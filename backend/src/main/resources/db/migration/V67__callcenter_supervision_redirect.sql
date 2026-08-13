-- Fase 15 do plano Call Center Parte III — supervisão avançada.
-- 15.1: histórico de posição/canal no momento em que o chamador entrou na fila (D11-B).
ALTER TABLE cc_interactions
    ADD COLUMN position_on_join INT,
    ADD COLUMN channel_name VARCHAR(80);

-- 15.3: retirar chamada da fila e redirecionar (Redirect via AMI). O alvo pode ser uma
-- chamada em fila sem agente ainda atribuído, então agent_id precisa virar opcional; o
-- destino do redirect é registrado em target_queue_id (outra fila) ou target_agent_id
-- (agente específico) — sempre um dos dois, nunca os dois, para as novas ações
-- REDIRECT_QUEUE/REDIRECT_AGENT.
ALTER TABLE cc_supervision_actions
    ALTER COLUMN agent_id DROP NOT NULL,
    ADD COLUMN target_queue_id BIGINT REFERENCES cc_queues(id),
    ADD COLUMN target_agent_id BIGINT REFERENCES cc_agents(id);
