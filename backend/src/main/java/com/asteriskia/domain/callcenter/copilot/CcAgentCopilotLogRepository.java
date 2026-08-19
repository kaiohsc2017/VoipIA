package com.asteriskia.domain.callcenter.copilot;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcAgentCopilotLogRepository extends JpaRepository<CcAgentCopilotLog, Long> {
    List<CcAgentCopilotLog> findByAgentIdOrderByCreatedAtDesc(Long agentId);
    List<CcAgentCopilotLog> findByInteractionIdOrderByCreatedAtAsc(String interactionId);
}
