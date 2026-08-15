package com.asteriskia.domain.insights;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

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
    // businessUnitIds: null = sem restrição (ADMIN); fail-open para gravação sem
    // ccRecordingId/sem BU atribuída — mesmo padrão de InsightsSpecifications.restrictedToBusinessUnits
    // (BU fechada em 2026-08-15 no dashboard do Call Center).
    @Query("SELECT ci.criticidade, COUNT(ci) FROM CallInsight ci " +
           "JOIN CallAudioFile caf ON caf.id = ci.audioFileId WHERE caf.source = :source " +
           "AND (:businessUnitIds IS NULL OR caf.ccRecordingId IS NULL OR caf.ccRecordingId IN " +
           "(SELECT r.id FROM CcRecording r WHERE r.businessUnit IS NULL OR r.businessUnit.id IN :businessUnitIds)) " +
           "GROUP BY ci.criticidade")
    List<Object[]> countByCriticidade(@Param("source") String source, @Param("businessUnitIds") Set<Integer> businessUnitIds);

    @Query("SELECT ci.categoriaAssunto, COUNT(ci) FROM CallInsight ci " +
           "JOIN CallAudioFile caf ON caf.id = ci.audioFileId " +
           "WHERE ci.categoriaAssunto IS NOT NULL AND caf.source = :source " +
           "AND (:businessUnitIds IS NULL OR caf.ccRecordingId IS NULL OR caf.ccRecordingId IN " +
           "(SELECT r.id FROM CcRecording r WHERE r.businessUnit IS NULL OR r.businessUnit.id IN :businessUnitIds)) " +
           "GROUP BY ci.categoriaAssunto ORDER BY COUNT(ci) DESC")
    List<Object[]> countByCategoria(@Param("source") String source, @Param("businessUnitIds") Set<Integer> businessUnitIds);
}
