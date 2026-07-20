package com.asteriskia.domain.insights;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CallInsightRepository extends JpaRepository<CallInsight, Long> {

    Optional<CallInsight> findByAudioFileId(Long audioFileId);

    void deleteByAudioFileId(Long audioFileId);

    List<CallInsight> findByAudioFileIdIn(List<Long> audioFileIds);

    @Query("SELECT ci.audioFileId FROM CallInsight ci WHERE LOWER(ci.categoriaAssunto) = LOWER(:categoria)")
    List<Long> findAudioFileIdsByCategoria(@Param("categoria") String categoria);

    @Query("SELECT ci.audioFileId FROM CallInsight ci WHERE LOWER(ci.criticidade) = LOWER(:criticidade)")
    List<Long> findAudioFileIdsByCriticidade(@Param("criticidade") String criticidade);

    // Restrito a source='verint' (Fase 3 do Quality Management, V40) — o dashboard de
    // Insights sempre mostrou só o call center Verint; uploads do portal do supervisor
    // não podem poluir esse agregado.
    @Query("SELECT ci.criticidade, COUNT(ci) FROM CallInsight ci " +
           "JOIN CallAudioFile caf ON caf.id = ci.audioFileId WHERE caf.source = 'verint' GROUP BY ci.criticidade")
    List<Object[]> countByCriticidade();

    @Query("SELECT ci.categoriaAssunto, COUNT(ci) FROM CallInsight ci " +
           "JOIN CallAudioFile caf ON caf.id = ci.audioFileId " +
           "WHERE ci.categoriaAssunto IS NOT NULL AND caf.source = 'verint' " +
           "GROUP BY ci.categoriaAssunto ORDER BY COUNT(ci) DESC")
    List<Object[]> countByCategoria();
}
