package com.asteriskia.domain.callcenter.flow.engine;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcFlowExecutionStepRepository extends JpaRepository<CcFlowExecutionStep, Long> {
    List<CcFlowExecutionStep> findByExecutionIdOrderByEnteredAtAsc(Long executionId);
}
