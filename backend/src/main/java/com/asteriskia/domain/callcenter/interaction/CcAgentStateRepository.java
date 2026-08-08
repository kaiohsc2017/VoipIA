package com.asteriskia.domain.callcenter.interaction;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CcAgentStateRepository extends JpaRepository<CcAgentState, Long> {
    Optional<CcAgentState> findByAgentIdAndEndedAtIsNull(Long agentId);

    List<CcAgentState> findByEndedAtIsNull();

    /** Períodos do agente que se sobrepõem a [from, to) — base do agregado diário de ocupação
     * (Fase 9b). Um período aberto (endedAt null) "vale até agora", então sempre se sobrepõe a
     * qualquer intervalo que termine no passado ou no presente. */
    @Query("SELECT s FROM CcAgentState s WHERE s.agent.id = :agentId AND s.startedAt < :to "
            + "AND (s.endedAt IS NULL OR s.endedAt > :from)")
    List<CcAgentState> findOverlapping(@Param("agentId") Long agentId,
                                        @Param("from") LocalDateTime from,
                                        @Param("to") LocalDateTime to);
}
