package com.asteriskia.domain.callcenter;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcQueueMemberRepository extends JpaRepository<CcQueueMember, Long> {
    List<CcQueueMember> findByQueueId(Long queueId);

    Optional<CcQueueMember> findByQueueIdAndAgentId(Long queueId, Long agentId);
}
