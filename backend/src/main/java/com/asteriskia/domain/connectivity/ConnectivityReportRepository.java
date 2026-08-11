package com.asteriskia.domain.connectivity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ConnectivityReportRepository — queries de exportação sem paginação (Fase 11).
 *
 * Separado do ConnectivityController para não poluir o repositório principal.
 */
@Repository
public interface ConnectivityReportRepository extends JpaRepository<TestResult, Long> {

    /**
     * Retorna todos os resultados que atendam os filtros opcionais, sem paginar.
     * Limitado implicitamente pelo período informado para evitar consultas ilimitadas.
     */
    @Query("SELECT r FROM TestResult r JOIN FETCH r.numberTest nt " +
           "WHERE (:status         IS NULL OR r.status              = :status) " +
           "AND   (:dateFrom       IS NULL OR r.executedAt          >= :dateFrom) " +
           "AND   (:dateTo         IS NULL OR r.executedAt          <= :dateTo) " +
           "AND   (:businessUnitId IS NULL OR nt.businessUnit.id    = :businessUnitId) " +
           "AND   (:clientId       IS NULL OR nt.client.id          = :clientId) " +
           "AND   (:operationId    IS NULL OR nt.operation.id       = :operationId) " +
           "AND   (:segmentId      IS NULL OR nt.segment.id         = :segmentId) " +
           "ORDER BY r.executedAt DESC")
    List<TestResult> findForExport(
            @Param("status")         String        status,
            @Param("dateFrom")       LocalDateTime dateFrom,
            @Param("dateTo")         LocalDateTime dateTo,
            @Param("businessUnitId") Long          businessUnitId,
            @Param("clientId")       Long          clientId,
            @Param("operationId")    Long          operationId,
            @Param("segmentId")      Long          segmentId
    );
}
