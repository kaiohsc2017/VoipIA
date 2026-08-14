package com.asteriskia.domain.callcenter.kb;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CcKbAnswerLogRepository extends JpaRepository<CcKbAnswerLog, Long> {

    @Query("select coalesce(sum(l.costUsd), 0) from CcKbAnswerLog l where l.createdAt between :from and :to")
    BigDecimal sumCostUsdBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Taxa de contenção do bot (§7 do plano-mãe) — proporção de perguntas respondidas sem
     * escalar para fila humana, no período informado. */
    @Query("select count(l) from CcKbAnswerLog l where l.createdAt between :from and :to and l.matched = true")
    long countMatchedBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("select count(l) from CcKbAnswerLog l where l.createdAt between :from and :to")
    long countTotalBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("select coalesce(sum(l.costUsd), 0) from CcKbAnswerLog l where l.session.id = :sessionId")
    BigDecimal sumCostUsdBySession(@Param("sessionId") Long sessionId);
}
