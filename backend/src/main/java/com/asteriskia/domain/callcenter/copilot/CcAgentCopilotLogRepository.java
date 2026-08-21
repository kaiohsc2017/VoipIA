package com.asteriskia.domain.callcenter.copilot;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcAgentCopilotLogRepository extends JpaRepository<CcAgentCopilotLog, Long> {

    /** Top-N derivado do Spring Data (traduzido para paginação real no banco, achado de
     * auditoria) — traz só os 30 mais recentes, em vez de
     * {@code findByAgentIdOrderByCreatedAtDesc} carregando o histórico inteiro do agente para só
     * então cortar em memória com {@code .limit(30)}. */
    List<CcAgentCopilotLog> findFirst30ByAgentIdOrderByCreatedAtDesc(Long agentId);

    List<CcAgentCopilotLog> findByInteractionIdOrderByCreatedAtAsc(String interactionId);
}
