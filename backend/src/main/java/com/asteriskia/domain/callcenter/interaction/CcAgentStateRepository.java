package com.asteriskia.domain.callcenter.interaction;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcAgentStateRepository extends JpaRepository<CcAgentState, Long> {
    Optional<CcAgentState> findByAgentIdAndEndedAtIsNull(Long agentId);

    List<CcAgentState> findByEndedAtIsNull();
}
