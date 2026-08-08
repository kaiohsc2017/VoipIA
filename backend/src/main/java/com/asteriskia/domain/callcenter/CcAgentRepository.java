package com.asteriskia.domain.callcenter;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface CcAgentRepository extends JpaRepository<CcAgent, Long>, JpaSpecificationExecutor<CcAgent> {
    Optional<CcAgent> findByUserId(Integer userId);

    /** Agentes ativos — base do agregado diário por agente (Fase 9b), mesmo padrão de
     * {@code CcQueueRepository.findByActiveTrue}. */
    List<CcAgent> findByActiveTrue();
}
