package com.asteriskia.domain.callcenter.flow;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface CcFlowRepository extends JpaRepository<CcFlow, Long>, JpaSpecificationExecutor<CcFlow> {
    Optional<CcFlow> findByName(String name);

    Optional<CcFlow> findByEntryExtension(String entryExtension);

    /** Lock pessimista usado por publish()/rollback() — evita duas requisições concorrentes
     * promovendo versões diferentes a PUBLISHED do mesmo fluxo (o índice único parcial em
     * cc_flow_versions é o backstop de banco para a mesma invariante). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from CcFlow f where f.id = :id")
    Optional<CcFlow> findByIdForUpdate(Long id);
}
