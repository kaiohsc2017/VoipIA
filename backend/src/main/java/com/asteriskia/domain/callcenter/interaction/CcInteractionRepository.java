package com.asteriskia.domain.callcenter.interaction;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcInteractionRepository extends JpaRepository<CcInteraction, Long> {
    Optional<CcInteraction> findByChannelUniqueId(String channelUniqueId);

    Optional<CcInteraction> findByAgentIdAndEndedAtIsNull(Long agentId);

    boolean existsByChannelUniqueId(String channelUniqueId);

    /** Interação mais recente do agente, já encerrada, ainda sem tabulação (aguardando ACW). */
    Optional<CcInteraction> findFirstByAgentIdAndEndedAtIsNotNullAndDispositionIsNullOrderByEndedAtDesc(
            Long agentId);

    /** Interações do dia de uma fila — base do painel de supervisão (Fase 6). */
    List<CcInteraction> findByQueueIdAndQueuedAtAfter(Long queueId, LocalDateTime since);

    /** Interações do dia atendidas por um agente — contagem de chamadas do painel. */
    long countByAgentIdAndAnsweredAtAfter(Long agentId, LocalDateTime since);
}
