package com.asteriskia.domain.callcenter;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CcQueueRepository extends JpaRepository<CcQueue, Long>, JpaSpecificationExecutor<CcQueue> {
    Optional<CcQueue> findByName(String name);

    /** Filas ativas — universo considerado pelo agregado diário (Fase 9a), evita gerar
     * relatório de filas desativadas/descontinuadas. */
    List<CcQueue> findByActiveTrue();

    /** Quantas filas já cadastradas ficam fora de [start, end] (Fase 19 — D20). */
    @Query(value = """
        SELECT count(*) FROM cc_queues
        WHERE name::int NOT BETWEEN :start AND :end
        """, nativeQuery = true)
    long countOutsideRange(@Param("start") int start, @Param("end") int end);
}
