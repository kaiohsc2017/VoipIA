package com.asteriskia.domain.callcenter.interaction;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CcInteractionRepository
        extends JpaRepository<CcInteraction, Long>, JpaSpecificationExecutor<CcInteraction> {
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

    /** Interações de uma fila num intervalo de `queuedAt` — base do agregado diário (Fase 9a). */
    List<CcInteraction> findByQueueIdAndQueuedAtBetween(Long queueId, LocalDateTime from, LocalDateTime to);

    /** Interações de um agente num intervalo de `queuedAt` — base do agregado diário por
     * agente (Fase 9b), mesmo padrão de {@link #findByQueueIdAndQueuedAtBetween}. */
    List<CcInteraction> findByAgentIdAndQueuedAtBetween(Long agentId, LocalDateTime from, LocalDateTime to);

    /** Todas as interações do período, sem filtro de fila/agente — base do "Perfil do cliente"
     * (Fase 27), que agrupa por ANI normalizado entre toda a operação. */
    List<CcInteraction> findByQueuedAtBetween(LocalDateTime from, LocalDateTime to);

    /** Ids de interações atendidas cujo tempo de espera (answered_at - queued_at) cai no
     * intervalo informado, em segundos — filtro "tempo de espera" do relatório analítico de
     * chamada (Fase 9c). Nunca atendidas (answered_at nulo) não têm tempo de espera definido,
     * ficam de fora por construção. Combinado depois via {@code id IN (...)} — mesmo padrão de
     * pré-filtro por id já usado em CallTranscriptSegmentRepository.findAudioFileIdsByTextSearch. */
    @Query(value = "SELECT id FROM cc_interactions WHERE answered_at IS NOT NULL "
                    + "AND EXTRACT(EPOCH FROM (answered_at - queued_at)) BETWEEN :min AND :max",
            nativeQuery = true)
    List<Long> findIdsByWaitSecondsBetween(@Param("min") long min, @Param("max") long max);

    /** Histórico de contatos anteriores no screen pop (Fase 14) — últimas interações do mesmo
     * contato identificado, excluída a interação atual. */
    List<CcInteraction> findTop10ByResolvedAdSamAndIdNotOrderByQueuedAtDesc(String resolvedAdSam, Long excludedId);

    /** Histórico unificado voz+chat (Fase 16.1, {@code CallCenterContactHistoryService}) — todas
     * as interações do contato, sem exclusão (a interação/sessão atual é excluída em memória pelo
     * serviço, depois de já ter feito o merge com o lado do chat). */
    List<CcInteraction> findByResolvedAdSamOrderByQueuedAtDesc(String resolvedAdSam);
}
