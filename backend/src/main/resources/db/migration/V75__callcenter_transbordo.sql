-- Fase 5e.2 do plano de fechamento 5/7/9 do Call Center — transbordo (overflow) de fila e nó de
-- fluxo "transferir_ramal" (ambos desbloqueados por esta migration).
--
-- Transbordo: uma fila pode apontar para outra fila de destino quando o tempo de espera ou o
-- tamanho da fila de espera excede um limiar configurável. Aditivo, todas as colunas nullable —
-- fila sem nenhuma delas configurada mantém o comportamento atual (sem transbordo automático).
-- FK auto-referenciada com ON DELETE SET NULL: remover a fila de destino do transbordo nunca
-- pode derrubar a fila de origem (ela só perde a configuração de transbordo, volta a se
-- comportar como antes).

ALTER TABLE cc_queues
    ADD COLUMN overflow_queue_id      BIGINT REFERENCES cc_queues(id) ON DELETE SET NULL,
    ADD COLUMN overflow_after_seconds INTEGER,
    ADD COLUMN overflow_max_waiting   INTEGER;

-- Laço A->B->A (ou mais longo) é rejeitado em aplicação (CallCenterQueueService, percorrendo a
-- cadeia overflow_queue_id até achar NULL ou repetir um id já visto) — não dá para expressar essa
-- checagem num CHECK constraint simples do Postgres, que não enxerga outras linhas da mesma
-- tabela nem percorre grafo.
