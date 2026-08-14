package com.asteriskia.domain.callcenter.flow.engine;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcFlowExecutionRepository extends JpaRepository<CcFlowExecution, Long> {
    Page<CcFlowExecution> findByFlowIdOrderByStartedAtDesc(Long flowId, Pageable pageable);

    List<CcFlowExecution> findByFlowId(Long flowId);

    /** Usado pelo controller para confirmar que a execução pertence ao fluxo informado antes de
     * expor o traço — sem isso, um usuário com acesso a um fluxo poderia ler o traço de execução
     * de outro fluxo/BU só adivinhando o id da execução. */
    Optional<CcFlowExecution> findByIdAndFlowId(Long id, Long flowId);

    /** Execução de fluxo correspondente a uma interação — base do enriquecimento "fluxo/URA" e
     * "opção escolhida" do relatório analítico de chamada (Fase 9c). Nula quando a interação não
     * passou pelo motor de fluxo visual (ex.: rota legada de URA por ramal 2XXX). */
    Optional<CcFlowExecution> findByInteractionId(Long interactionId);
}
