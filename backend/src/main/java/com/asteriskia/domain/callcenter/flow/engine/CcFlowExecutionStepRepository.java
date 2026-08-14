package com.asteriskia.domain.callcenter.flow.engine;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcFlowExecutionStepRepository extends JpaRepository<CcFlowExecutionStep, Long> {
    List<CcFlowExecutionStep> findByExecutionIdOrderByEnteredAtAsc(Long executionId);

    /** Todos os passos de nó "menu_opcoes" com aresta já resolvida — base do relatório analítico
     * de chamada (Fase 9c) para descobrir a opção escolhida (dígito + rótulo), resolvendo o
     * {@code sourceHandle} da aresta no grafo da versão em vez de adivinhar por regex sobre o id
     * (ver {@code CallCenterDetailReportService}). */
    List<CcFlowExecutionStep> findByNodeTypeAndTakenEdgeIsNotNull(String nodeType);
}
