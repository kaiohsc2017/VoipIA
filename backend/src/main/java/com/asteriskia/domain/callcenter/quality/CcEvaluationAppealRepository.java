package com.asteriskia.domain.callcenter.quality;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CcEvaluationAppealRepository extends JpaRepository<CcEvaluationAppeal, Long> {

    List<CcEvaluationAppeal> findByAgentIdOrderByCreatedAtDesc(Long agentId);

    List<CcEvaluationAppeal> findByEvaluationId(Long evaluationId);

    Optional<CcEvaluationAppeal> findByEvaluationIdAndAgentId(Long evaluationId, Long agentId);

    List<CcEvaluationAppeal> findByStatusOrderByCreatedAtDesc(String status);

    @Query("SELECT a FROM CcEvaluationAppeal a JOIN FETCH a.evaluation JOIN FETCH a.agent WHERE a.status = :status ORDER BY a.createdAt DESC")
    List<CcEvaluationAppeal> findAllPendingWithDetails(@Param("status") String status);
}
