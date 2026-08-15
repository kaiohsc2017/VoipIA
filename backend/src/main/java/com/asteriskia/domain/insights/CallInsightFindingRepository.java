package com.asteriskia.domain.insights;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;

@Repository
public interface CallInsightFindingRepository extends JpaRepository<CallInsightFinding, Long> {

    List<CallInsightFinding> findByAudioFileIdOrderByIdAsc(Long audioFileId);

    void deleteByAudioFileId(Long audioFileId);

    // source parametrizado (Fase 8 do Call Center) — ver CallInsightRepository.countByCriticidade.
    // businessUnitIds: null = sem restrição (ADMIN); fail-open (BU fechada em 2026-08-15).
    @Query("SELECT f.tipo, COUNT(f) FROM CallInsightFinding f " +
           "JOIN CallAudioFile caf ON caf.id = f.audioFileId WHERE caf.source = :source " +
           "AND (:businessUnitIds IS NULL OR caf.ccRecordingId IS NULL OR caf.ccRecordingId IN " +
           "(SELECT r.id FROM CcRecording r WHERE r.businessUnit IS NULL OR r.businessUnit.id IN :businessUnitIds)) " +
           "GROUP BY f.tipo")
    List<Object[]> countByTipo(@Param("source") String source, @Param("businessUnitIds") Set<Integer> businessUnitIds);

    @Query("SELECT DISTINCT f.audioFileId FROM CallInsightFinding f WHERE LOWER(f.tipo) = LOWER(:tipo)")
    List<Long> findAudioFileIdsByTipo(@Param("tipo") String tipo);

    /** Achados por tipo de um agente num período — base do relatório de performance (V39). */
    @Query("SELECT f.tipo, COUNT(f) FROM CallInsightFinding f JOIN CallAudioFile caf ON caf.id = f.audioFileId " +
           "WHERE caf.agentName = :agentName AND caf.source = :source AND caf.callStarttime BETWEEN :from AND :to GROUP BY f.tipo")
    List<Object[]> countByTipoForAgentPeriod(@Param("agentName") String agentName,
                                              @Param("source") String source,
                                              @Param("from") java.time.LocalDateTime from,
                                              @Param("to") java.time.LocalDateTime to);

    /** Achados mais graves de um agente num período (urgente/alta primeiro) — contexto
     * bruto para o LLM narrar recomendações, sem que ele precise inventar exemplos. */
    @Query("SELECT f FROM CallInsightFinding f JOIN CallAudioFile caf ON caf.id = f.audioFileId " +
           "WHERE caf.agentName = :agentName AND caf.source = :source AND caf.callStarttime BETWEEN :from AND :to " +
           "ORDER BY CASE f.prioridade WHEN 'urgente' THEN 0 WHEN 'alta' THEN 1 WHEN 'media' THEN 2 ELSE 3 END, f.id DESC")
    List<CallInsightFinding> findTopForAgentPeriod(@Param("agentName") String agentName,
                                                    @Param("source") String source,
                                                    @Param("from") java.time.LocalDateTime from,
                                                    @Param("to") java.time.LocalDateTime to,
                                                    org.springframework.data.domain.Pageable pageable);
}
