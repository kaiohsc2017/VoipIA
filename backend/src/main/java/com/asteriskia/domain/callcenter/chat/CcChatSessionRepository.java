package com.asteriskia.domain.callcenter.chat;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcChatSessionRepository extends JpaRepository<CcChatSession, Long> {

    List<CcChatSession> findByQueueIdAndStatusOrderByStartedAtAsc(Long queueId, String status);

    List<CcChatSession> findByAssignedAgentIdAndStatusOrderByClaimedAtAsc(Long assignedAgentId, String status);
}
