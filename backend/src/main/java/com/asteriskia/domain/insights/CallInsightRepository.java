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

    @Query("SELECT ci.criticidade, COUNT(ci) FROM CallInsight ci GROUP BY ci.criticidade")
    List<Object[]> countByCriticidade();

    @Query("SELECT ci.categoriaAssunto, COUNT(ci) FROM CallInsight ci " +
           "WHERE ci.categoriaAssunto IS NOT NULL GROUP BY ci.categoriaAssunto ORDER BY COUNT(ci) DESC")
    List<Object[]> countByCategoria();
}
