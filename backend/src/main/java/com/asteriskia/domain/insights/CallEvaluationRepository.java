package com.asteriskia.domain.insights;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface CallEvaluationRepository extends JpaRepository<CallEvaluation, Long> {

    Optional<CallEvaluation> findByAudioFileId(Long audioFileId);

    boolean existsByScorecardId(Long scorecardId);

    void deleteByAudioFileId(Long audioFileId);

    List<CallEvaluation> findByAudioFileIdIn(List<Long> audioFileIds);

    @Query("SELECT ce.audioFileId FROM CallEvaluation ce WHERE ce.notaTotal >= :notaMin")
    List<Long> findAudioFileIdsByNotaMin(@Param("notaMin") BigDecimal notaMin);

    @Query("SELECT ce.audioFileId FROM CallEvaluation ce WHERE ce.notaTotal <= :notaMax")
    List<Long> findAudioFileIdsByNotaMax(@Param("notaMax") BigDecimal notaMax);

    @Query("SELECT ce.audioFileId FROM CallEvaluation ce WHERE ce.isFailed = :isFailed")
    List<Long> findAudioFileIdsByIsFailed(@Param("isFailed") Boolean isFailed);

    // source parametrizado (Fase 8 do Call Center) — ver CallInsightRepository.countByCriticidade.
    // uploads do portal do supervisor continuam de fora (nota/auto-fail vistos na tela
    // própria "Meus Envios"). businessUnitIds: null = sem restrição (ADMIN); fail-open
    // (BU fechada em 2026-08-15).
    @Query("SELECT caf.agentName, AVG(ce.notaTotal) FROM CallEvaluation ce " +
           "JOIN CallAudioFile caf ON caf.id = ce.audioFileId " +
           "WHERE caf.agentName IS NOT NULL AND caf.source = :source " +
           "AND (:businessUnitIds IS NULL OR caf.ccRecordingId IS NULL OR caf.ccRecordingId IN " +
           "(SELECT r.id FROM CcRecording r WHERE r.businessUnit IS NULL OR r.businessUnit.id IN :businessUnitIds)) " +
           "GROUP BY caf.agentName")
    List<Object[]> averageNotaByAgent(@Param("source") String source, @Param("businessUnitIds") Set<Integer> businessUnitIds);

    @Query("SELECT COUNT(ce) FROM CallEvaluation ce JOIN CallAudioFile caf ON caf.id = ce.audioFileId " +
           "WHERE ce.isFailed = true AND caf.source = :source " +
           "AND (:businessUnitIds IS NULL OR caf.ccRecordingId IS NULL OR caf.ccRecordingId IN " +
           "(SELECT r.id FROM CcRecording r WHERE r.businessUnit IS NULL OR r.businessUnit.id IN :businessUnitIds))")
    long countFailed(@Param("source") String source, @Param("businessUnitIds") Set<Integer> businessUnitIds);

    /** Nota total média de um agente num período — base do relatório de performance (V39).
     * source parametrizado (Fase 8 do Call Center) — nunca mistura verint/callcenter mesmo
     * que o agentName coincida entre os dois universos. */
    @Query("SELECT AVG(ce.notaTotal) FROM CallEvaluation ce JOIN CallAudioFile caf ON caf.id = ce.audioFileId " +
           "WHERE caf.agentName = :agentName AND caf.source = :source AND caf.callStarttime BETWEEN :from AND :to")
    BigDecimal averageNotaForAgentPeriod(@Param("agentName") String agentName,
                                         @Param("source") String source,
                                         @Param("from") java.time.LocalDateTime from,
                                         @Param("to") java.time.LocalDateTime to);

    /** Contagem de auto-fails de um agente num período — base do relatório de performance (V39). */
    @Query("SELECT COUNT(ce) FROM CallEvaluation ce JOIN CallAudioFile caf ON caf.id = ce.audioFileId " +
           "WHERE caf.agentName = :agentName AND caf.source = :source AND caf.callStarttime BETWEEN :from AND :to AND ce.isFailed = true")
    long countFailedForAgentPeriod(@Param("agentName") String agentName,
                                    @Param("source") String source,
                                    @Param("from") java.time.LocalDateTime from,
                                    @Param("to") java.time.LocalDateTime to);
}
