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

    @Query("SELECT f.tipo, COUNT(f) FROM CallInsightFinding f GROUP BY f.tipo")
    List<Object[]> countByTipo();

    @Query("SELECT DISTINCT f.audioFileId FROM CallInsightFinding f WHERE LOWER(f.tipo) = LOWER(:tipo)")
    List<Long> findAudioFileIdsByTipo(@Param("tipo") String tipo);
}
