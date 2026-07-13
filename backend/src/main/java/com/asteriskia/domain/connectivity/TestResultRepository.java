package com.asteriskia.domain.connectivity;

import java.time.LocalDateTime;
import java.util.Collection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface TestResultRepository extends JpaRepository<TestResult, Long> {

    /**
     * Query unificada com todos os filtros opcionais. Cada parâmetro é ignorado quando NULL —
     * permite qualquer combinação de filtros sem explodir em if/else.
     *
     * <p>dateFrom/dateTo usam CAST explícito no lado do "IS NULL": sem o cast, o Postgres não
     * consegue inferir o tipo desse parâmetro (só aparece em "? IS NULL", sem nenhuma outra coluna
     * pra deduzir o tipo) e falha com "ERROR: could not determine data type of parameter $N" sempre
     * que a busca é feita sem informar as duas datas.
     *
     * <p>allowedBusinessUnitIds: controle de acesso por BU (não é filtro opcional do usuário) —
     * null significa ADMIN/sem restrição; para usuário restrito, o controller sempre informa a
     * lista das BUs do usuário, mesmo sem o filtro businessUnitId explícito ter sido escolhido na
     * tela.
     */
    @Query(
            "SELECT r FROM TestResult r JOIN r.numberTest nt "
                    + "WHERE (:numberTestId  IS NULL OR nt.id                  = :numberTestId) "
                    + "AND   (:status        IS NULL OR r.status               = :status) "
                    + "AND   (CAST(:dateFrom AS timestamp) IS NULL OR r.executedAt >= :dateFrom) "
                    + "AND   (CAST(:dateTo   AS timestamp) IS NULL OR r.executedAt <= :dateTo) "
                    + "AND   (:businessUnitId IS NULL OR nt.businessUnit.id    = :businessUnitId) "
                    + "AND   (:clientId      IS NULL OR nt.client.id           = :clientId) "
                    + "AND   (:operationId   IS NULL OR nt.operation.id        = :operationId) "
                    + "AND   (:segmentId     IS NULL OR nt.segment.id          = :segmentId) "
                    + "AND   (:allowedBusinessUnitIds IS NULL OR nt.businessUnit.id IN :allowedBusinessUnitIds)")
    Page<TestResult> findWithFilters(
            @Param("numberTestId") Long numberTestId,
            @Param("status") String status,
            @Param("dateFrom") LocalDateTime dateFrom,
            @Param("dateTo") LocalDateTime dateTo,
            @Param("businessUnitId") Long businessUnitId,
            @Param("clientId") Long clientId,
            @Param("operationId") Long operationId,
            @Param("segmentId") Long segmentId,
            @Param("allowedBusinessUnitIds") Collection<Long> allowedBusinessUnitIds,
            Pageable pageable);

    // Mantidos para compatibilidade com HistoricoModal (filtra por numberTestId + período)
    Page<TestResult> findByNumberTestId(Long numberTestId, Pageable pageable);

    Page<TestResult> findByNumberTestIdAndExecutedAtBetween(
            Long numberTestId, LocalDateTime from, LocalDateTime to, Pageable pageable);

    // Estatísticas para o dashboard
    @Query("SELECT COUNT(r) FROM TestResult r WHERE r.executedAt BETWEEN :from AND :to")
    long countByPeriod(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query(
            "SELECT COUNT(r) FROM TestResult r WHERE r.status = :status AND r.executedAt BETWEEN :from AND :to")
    long countByStatusAndPeriod(
            @Param("status") String status,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);
}
