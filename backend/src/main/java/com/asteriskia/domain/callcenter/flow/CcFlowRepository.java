package com.asteriskia.domain.callcenter.flow;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CcFlowRepository extends JpaRepository<CcFlow, Long>, JpaSpecificationExecutor<CcFlow> {
    Optional<CcFlow> findByName(String name);

    Optional<CcFlow> findByEntryExtension(String entryExtension);

    /** Base do agregado diário de fluxo/URA (Fase 9c.1) — só fluxos ativos, mesmo padrão de
     * {@code CcQueueRepository.findByActiveTrue()} usado na Fase 9a. */
    List<CcFlow> findByActiveTrue();

    /** Lock pessimista usado por publish()/rollback() — evita duas requisições concorrentes
     * promovendo versões diferentes a PUBLISHED do mesmo fluxo (o índice único parcial em
     * cc_flow_versions é o backstop de banco para a mesma invariante). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from CcFlow f where f.id = :id")
    Optional<CcFlow> findByIdForUpdate(Long id);

    /** Quantos fluxos já cadastrados têm ramal de entrada fora de [start, end] (Fase 19 — D20). */
    @Query(value = """
        SELECT count(*) FROM cc_flows
        WHERE entry_extension IS NOT NULL
          AND entry_extension::int NOT BETWEEN :start AND :end
        """, nativeQuery = true)
    long countOutsideRange(@Param("start") int start, @Param("end") int end);
}
