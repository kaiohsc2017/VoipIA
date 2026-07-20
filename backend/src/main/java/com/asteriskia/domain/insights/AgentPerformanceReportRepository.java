package com.asteriskia.domain.insights;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AgentPerformanceReportRepository extends JpaRepository<AgentPerformanceReport, Long> {

    Page<AgentPerformanceReport> findByRequestedByOrderByRequestedAtDesc(String requestedBy, Pageable pageable);

    Page<AgentPerformanceReport> findAllByOrderByRequestedAtDesc(Pageable pageable);

    /** Último pedido (de qualquer status) do par supervisor/atendente — base da checagem de cooldown. */
    Optional<AgentPerformanceReport> findFirstByRequestedByAndAgentNameOrderByRequestedAtDesc(String requestedBy, String agentName);

    /** Último relatório concluído do agente (de qualquer solicitante) — vira previous_report_id do novo pedido. */
    Optional<AgentPerformanceReport> findFirstByAgentNameAndStatusOrderByCompletedAtDesc(String agentName, String status);

    List<AgentPerformanceReport> findByStatus(String status);

    boolean existsByAgentNameAndRequestedBy(String agentName, String requestedBy);

    /** IDs dos relatórios que este supervisor pediu para o agente — usado para restringir
     * o histórico de evolução (agent_evolution_snapshots) só aos próprios pedidos,
     * mesma regra de posse do resto da Fase 2 (não-ADMIN nunca vê dado de outro supervisor). */
    @org.springframework.data.jpa.repository.Query("SELECT r.id FROM AgentPerformanceReport r WHERE r.agentName = :agentName AND r.requestedBy = :requestedBy")
    List<Long> findIdsByAgentNameAndRequestedBy(@org.springframework.data.repository.query.Param("agentName") String agentName,
                                                 @org.springframework.data.repository.query.Param("requestedBy") String requestedBy);
}
