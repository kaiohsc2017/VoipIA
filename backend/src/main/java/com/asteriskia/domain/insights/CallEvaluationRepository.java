package com.asteriskia.domain.insights;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

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

    // Restrito a source='verint' (Fase 3 do Quality Management, V40) — dashboard de
    // Insights é sempre sobre o call center Verint; uploads do portal do supervisor
    // não entram nesse agregado (eles têm nota/auto-fail próprios, vistos por chamada
    // na tela "Meus Envios").
    @Query("SELECT caf.agentName, AVG(ce.notaTotal) FROM CallEvaluation ce " +
           "JOIN CallAudioFile caf ON caf.id = ce.audioFileId " +
           "WHERE caf.agentName IS NOT NULL AND caf.source = 'verint' GROUP BY caf.agentName")
    List<Object[]> averageNotaByAgent();

    @Query("SELECT COUNT(ce) FROM CallEvaluation ce JOIN CallAudioFile caf ON caf.id = ce.audioFileId " +
           "WHERE ce.isFailed = true AND caf.source = 'verint'")
    long countFailed();

    /** Nota total média de um agente num período — base do relatório de performance (V39). */
    @Query("SELECT AVG(ce.notaTotal) FROM CallEvaluation ce JOIN CallAudioFile caf ON caf.id = ce.audioFileId " +
           "WHERE caf.agentName = :agentName AND caf.callStarttime BETWEEN :from AND :to")
    BigDecimal averageNotaForAgentPeriod(@Param("agentName") String agentName,
                                         @Param("from") java.time.LocalDateTime from,
                                         @Param("to") java.time.LocalDateTime to);

    /** Contagem de auto-fails de um agente num período — base do relatório de performance (V39). */
    @Query("SELECT COUNT(ce) FROM CallEvaluation ce JOIN CallAudioFile caf ON caf.id = ce.audioFileId " +
           "WHERE caf.agentName = :agentName AND caf.callStarttime BETWEEN :from AND :to AND ce.isFailed = true")
    long countFailedForAgentPeriod(@Param("agentName") String agentName,
                                    @Param("from") java.time.LocalDateTime from,
                                    @Param("to") java.time.LocalDateTime to);
}
