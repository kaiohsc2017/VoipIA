package com.asteriskia.domain.insights;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AgentEvolutionSnapshotRepository extends JpaRepository<AgentEvolutionSnapshot, Long> {

    List<AgentEvolutionSnapshot> findByAgentNameOrderByCreatedAtAsc(String agentName);

    List<AgentEvolutionSnapshot> findByReportId(Long reportId);
}
