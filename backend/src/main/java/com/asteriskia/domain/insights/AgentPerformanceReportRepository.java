package com.asteriskia.domain.insights;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AgentPerformanceReportRepository extends JpaRepository<AgentPerformanceReport, Long> {

    Page<AgentPerformanceReport> findByRequestedByAndSourceOrderByRequestedAtDesc(String requestedBy, String source, Pageable pageable);

    Page<AgentPerformanceReport> findBySourceOrderByRequestedAtDesc(String source, Pageable pageable);

    /** Último pedido (de qualquer status) do par supervisor/atendente numa origem — base da checagem de cooldown. */
    Optional<AgentPerformanceReport> findFirstByRequestedByAndAgentNameAndSourceOrderByRequestedAtDesc(
            String requestedBy, String agentName, String source);

    /** Último relatório concluído do agente numa origem (de qualquer solicitante) — vira previous_report_id do novo pedido. */
    Optional<AgentPerformanceReport> findFirstByAgentNameAndSourceAndStatusOrderByCompletedAtDesc(
            String agentName, String source, String status);

    List<AgentPerformanceReport> findByStatus(String status);

    boolean existsByAgentNameAndRequestedByAndSource(String agentName, String requestedBy, String source);

    /** IDs dos relatórios que este supervisor pediu para o agente numa origem — usado para
     * restringir o histórico de evolução (agent_evolution_snapshots) só aos próprios pedidos,
     * mesma regra de posse do resto da Fase 2 (não-ADMIN nunca vê dado de outro supervisor). */
    @org.springframework.data.jpa.repository.Query("SELECT r.id FROM AgentPerformanceReport r WHERE r.agentName = :agentName AND r.source = :source AND r.requestedBy = :requestedBy")
    List<Long> findIdsByAgentNameAndRequestedBy(@org.springframework.data.repository.query.Param("agentName") String agentName,
                                                 @org.springframework.data.repository.query.Param("source") String source,
                                                 @org.springframework.data.repository.query.Param("requestedBy") String requestedBy);
}
