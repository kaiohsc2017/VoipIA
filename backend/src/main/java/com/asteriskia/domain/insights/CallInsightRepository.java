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

    // source parametrizado (Fase 8 do Call Center) — dashboard de Insights usa 'verint',
    // dashboard de Insights do Call Center usa 'callcenter'; uploads do portal do supervisor
    // continuam de fora dos dois (tela própria "Meus Envios").
    @Query("SELECT ci.criticidade, COUNT(ci) FROM CallInsight ci " +
           "JOIN CallAudioFile caf ON caf.id = ci.audioFileId WHERE caf.source = :source GROUP BY ci.criticidade")
    List<Object[]> countByCriticidade(@Param("source") String source);

    @Query("SELECT ci.categoriaAssunto, COUNT(ci) FROM CallInsight ci " +
           "JOIN CallAudioFile caf ON caf.id = ci.audioFileId " +
           "WHERE ci.categoriaAssunto IS NOT NULL AND caf.source = :source " +
           "GROUP BY ci.categoriaAssunto ORDER BY COUNT(ci) DESC")
    List<Object[]> countByCategoria(@Param("source") String source);
}
