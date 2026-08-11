package com.asteriskia.domain.callcenter.reports;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcAggAgentDailyRepository extends JpaRepository<CcAggAgentDaily, Long> {

    /** Upsert via find-then-save (JPA puro) — mesmo racional de {@code CcAggQueueDailyRepository}. */
    Optional<CcAggAgentDaily> findByAgentIdAndDate(Long agentId, LocalDate date);

    List<CcAggAgentDaily> findByAgentIdAndDateBetweenOrderByDateAsc(Long agentId, LocalDate from, LocalDate to);

    List<CcAggAgentDaily> findByDateBetweenOrderByAgentIdAscDateAsc(LocalDate from, LocalDate to);
}
