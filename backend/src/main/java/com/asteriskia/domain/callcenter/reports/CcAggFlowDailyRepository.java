package com.asteriskia.domain.callcenter.reports;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcAggFlowDailyRepository extends JpaRepository<CcAggFlowDaily, Long> {
    Optional<CcAggFlowDaily> findByFlowIdAndDate(Long flowId, LocalDate date);

    List<CcAggFlowDaily> findByFlowIdAndDateBetweenOrderByDateAsc(Long flowId, LocalDate from, LocalDate to);

    List<CcAggFlowDaily> findByDateBetweenOrderByFlowIdAscDateAsc(LocalDate from, LocalDate to);
}
