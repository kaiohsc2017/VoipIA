package com.asteriskia.domain.callcenter;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface CcQueueRepository extends JpaRepository<CcQueue, Long>, JpaSpecificationExecutor<CcQueue> {
    Optional<CcQueue> findByName(String name);

    /** Filas ativas — universo considerado pelo agregado diário (Fase 9a), evita gerar
     * relatório de filas desativadas/descontinuadas. */
    List<CcQueue> findByActiveTrue();
}
