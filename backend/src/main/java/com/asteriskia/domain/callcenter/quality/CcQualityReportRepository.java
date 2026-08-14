package com.asteriskia.domain.callcenter.quality;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcQualityReportRepository extends JpaRepository<CcQualityReport, Long> {

    /** Última execução no mesmo escopo — base do cooldown e da resolução do relatório anterior
     * para comparação (Fase 26: "trava de 5 dias úteis entre execuções no mesmo escopo"). */
    Optional<CcQualityReport> findFirstByScopeTypeAndScopeValueAndSourceOrderByRequestedAtDesc(
            QualityReportScopeType scopeType, String scopeValue, String source);

    Page<CcQualityReport> findBySourceOrderByRequestedAtDesc(String source, Pageable pageable);
}
