package com.asteriskia.domain.callcenter.identity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CcIdentityResolutionLogRepository extends JpaRepository<CcIdentityResolutionLog, Long> {

    /** Consumido por {@code CostAlertService} para a frente "callcenter_identidade". */
    @Query("select coalesce(sum(l.aiCostUsd), 0) from CcIdentityResolutionLog l where l.resolvedAt between :from and :to")
    BigDecimal sumAiCostUsdBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
