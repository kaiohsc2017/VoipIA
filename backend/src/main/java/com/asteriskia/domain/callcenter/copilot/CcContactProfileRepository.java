package com.asteriskia.domain.callcenter.copilot;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CcContactProfileRepository extends JpaRepository<CcContactProfile, Long> {

    Optional<CcContactProfile> findFirstByResolvedAdSamOrderByGeneratedAtDesc(String resolvedAdSam);

    /** Consumido por {@code CostAlertService} para a frente "callcenter_copiloto". */
    @Query("select coalesce(sum(p.costUsd), 0) from CcContactProfile p where p.generatedAt between :from and :to")
    BigDecimal sumCostUsdBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
