package com.asteriskia.domain.callcenter.flow.engine;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcFlowExecutionStepRepository extends JpaRepository<CcFlowExecutionStep, Long> {
    List<CcFlowExecutionStep> findByExecutionIdOrderByEnteredAtAsc(Long executionId);

    /** Tela de traço de execução (Fase 5f.2): {@code cc_flow_execution_steps} é particionada por
     * mês em {@code entered_at} (migration V72) — filtrar só por {@code executionId} obrigaria o
     * Postgres a varrer TODAS as partições, já que o otimizador não sabe em qual mês os passos
     * daquela execução caem sem essa coluna na cláusula WHERE. O controller sempre passa a janela
     * de {@code startedAt}/{@code endedAt} da própria execução (com folga), permitindo pruning de
     * partição normal. */
    List<CcFlowExecutionStep> findByExecutionIdAndEnteredAtBetweenOrderByEnteredAtAsc(
            Long executionId, LocalDateTime from, LocalDateTime to);

    /** Todos os passos de nó "menu_opcoes" com aresta já resolvida — base do relatório analítico
     * de chamada (Fase 9c) para descobrir a opção escolhida (dígito + rótulo), resolvendo o
     * {@code sourceHandle} da aresta no grafo da versão em vez de adivinhar por regex sobre o id
     * (ver {@code CallCenterDetailReportService}). */
    List<CcFlowExecutionStep> findByNodeTypeAndTakenEdgeIsNotNull(String nodeType);
}
