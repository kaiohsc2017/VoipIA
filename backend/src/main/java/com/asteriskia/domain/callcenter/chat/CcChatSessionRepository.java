package com.asteriskia.domain.callcenter.chat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcChatSessionRepository extends JpaRepository<CcChatSession, Long> {

    /** Fase 7e — sessão aberta (não encerrada) para um chat_id do Telegram num canal específico
     * (mesma restrição do índice único parcial da V79: {@code channel_id, external_ref WHERE
     * closed_at IS NULL}). */
    Optional<CcChatSession> findByChannelIdAndExternalRefAndClosedAtIsNull(Long channelId, String externalRef);

    List<CcChatSession> findByQueueIdAndStatusOrderByStartedAtAsc(Long queueId, String status);

    List<CcChatSession> findByAssignedAgentIdAndStatusOrderByClaimedAtAsc(Long assignedAgentId, String status);

    /** Blending de chat (Fase 7c) — quantos chats o agente já tem em atendimento neste instante,
     * usado para decidir se um novo {@code claim} pode ser aceito. */
    long countByAssignedAgentIdAndStatus(Long assignedAgentId, String status);

    /** Todas as sessões do período — base do "Perfil do cliente" (Fase 27), mesmo padrão de
     * {@link com.asteriskia.domain.callcenter.interaction.CcInteractionRepository#findByQueuedAtBetween}. */
    List<CcChatSession> findByStartedAtBetween(LocalDateTime from, LocalDateTime to);

    /** Agregado diário de chat (Fase 9c.2) — sessões de uma fila iniciadas num dia. */
    List<CcChatSession> findByQueueIdAndStartedAtBetween(Long queueId, LocalDateTime from, LocalDateTime to);
}
