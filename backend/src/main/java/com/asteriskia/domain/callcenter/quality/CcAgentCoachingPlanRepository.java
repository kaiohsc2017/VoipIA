package com.asteriskia.domain.callcenter.quality;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcAgentCoachingPlanRepository extends JpaRepository<CcAgentCoachingPlan, Long> {

    List<CcAgentCoachingPlan> findByAgentIdOrderByCreatedAtDesc(Long agentId);

    List<CcAgentCoachingPlan> findByAgentIdAndStatusOrderByCreatedAtDesc(Long agentId, String status);

    List<CcAgentCoachingPlan> findByEvaluationId(Long evaluationId);

    List<CcAgentCoachingPlan> findByStatusOrderByDeadlineAsc(String status);
}
