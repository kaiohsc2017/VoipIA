package com.asteriskia.domain.callcenter.chat;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcChatSessionRepository extends JpaRepository<CcChatSession, Long> {

    List<CcChatSession> findByQueueIdAndStatusOrderByStartedAtAsc(Long queueId, String status);

    List<CcChatSession> findByAssignedAgentIdAndStatusOrderByClaimedAtAsc(Long assignedAgentId, String status);

    /** Blending de chat (Fase 7c) — quantos chats o agente já tem em atendimento neste instante,
     * usado para decidir se um novo {@code claim} pode ser aceito. */
    long countByAssignedAgentIdAndStatus(Long assignedAgentId, String status);

    /** Todas as sessões do período — base do "Perfil do cliente" (Fase 27), mesmo padrão de
     * {@link com.asteriskia.domain.callcenter.interaction.CcInteractionRepository#findByQueuedAtBetween}. */
    List<CcChatSession> findByStartedAtBetween(LocalDateTime from, LocalDateTime to);
}
