package com.asteriskia.domain.callcenter.reports;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcAggFlowNodeDailyRepository extends JpaRepository<CcAggFlowNodeDaily, Long> {
    Optional<CcAggFlowNodeDaily> findByFlowIdAndNodeIdAndDate(Long flowId, String nodeId, LocalDate date);

    /** Painel "abandono por nó" de um fluxo num período — sem agrupamento em banco, feito em
     * memória pelo service (volume esperado baixo, mesmo padrão de outras telas do domínio). */
    List<CcAggFlowNodeDaily> findByFlowIdAndDateBetween(Long flowId, LocalDate from, LocalDate to);
}
