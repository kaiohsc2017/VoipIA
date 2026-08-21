package com.asteriskia.domain.callcenter;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcQueueMemberRepository extends JpaRepository<CcQueueMember, Long> {
    List<CcQueueMember> findByQueueId(Long queueId);

    /** Contagem direta no banco (achado de auditoria) — evita materializar todas as entidades
     * de {@link #findByQueueId} só para descobrir o tamanho da lista. */
    long countByQueueId(Long queueId);

    Optional<CcQueueMember> findByQueueIdAndAgentId(Long queueId, Long agentId);

    /** Filas de um agente (Fase 12.4) — via inversa de findByQueueId, que já existia. */
    List<CcQueueMember> findByAgentId(Long agentId);
}
