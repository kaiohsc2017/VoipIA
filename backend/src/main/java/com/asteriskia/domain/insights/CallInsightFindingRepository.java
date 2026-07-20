package com.asteriskia.domain.insights;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CallInsightFindingRepository extends JpaRepository<CallInsightFinding, Long> {

    List<CallInsightFinding> findByAudioFileIdOrderByIdAsc(Long audioFileId);

    void deleteByAudioFileId(Long audioFileId);

    // Restrito a source='verint' (Fase 3 do Quality Management, V40) — dashboard de
    // Insights é sempre sobre o call center Verint; uploads do portal do supervisor
    // não entram nesse agregado.
    @Query("SELECT f.tipo, COUNT(f) FROM CallInsightFinding f " +
           "JOIN CallAudioFile caf ON caf.id = f.audioFileId WHERE caf.source = 'verint' GROUP BY f.tipo")
    List<Object[]> countByTipo();

    @Query("SELECT DISTINCT f.audioFileId FROM CallInsightFinding f WHERE LOWER(f.tipo) = LOWER(:tipo)")
    List<Long> findAudioFileIdsByTipo(@Param("tipo") String tipo);

    /** Achados por tipo de um agente num período — base do relatório de performance (V39). */
    @Query("SELECT f.tipo, COUNT(f) FROM CallInsightFinding f JOIN CallAudioFile caf ON caf.id = f.audioFileId " +
           "WHERE caf.agentName = :agentName AND caf.callStarttime BETWEEN :from AND :to GROUP BY f.tipo")
    List<Object[]> countByTipoForAgentPeriod(@Param("agentName") String agentName,
                                              @Param("from") java.time.LocalDateTime from,
                                              @Param("to") java.time.LocalDateTime to);

    /** Achados mais graves de um agente num período (urgente/alta primeiro) — contexto
     * bruto para o LLM narrar recomendações, sem que ele precise inventar exemplos. */
    @Query("SELECT f FROM CallInsightFinding f JOIN CallAudioFile caf ON caf.id = f.audioFileId " +
           "WHERE caf.agentName = :agentName AND caf.callStarttime BETWEEN :from AND :to " +
           "ORDER BY CASE f.prioridade WHEN 'urgente' THEN 0 WHEN 'alta' THEN 1 WHEN 'media' THEN 2 ELSE 3 END, f.id DESC")
    List<CallInsightFinding> findTopForAgentPeriod(@Param("agentName") String agentName,
                                                    @Param("from") java.time.LocalDateTime from,
                                                    @Param("to") java.time.LocalDateTime to,
                                                    org.springframework.data.domain.Pageable pageable);
}
