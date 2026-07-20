package com.asteriskia.domain.insights;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CallEvaluationItemRepository extends JpaRepository<CallEvaluationItem, Long> {

    List<CallEvaluationItem> findByEvaluationIdOrderByIdAsc(Long evaluationId);

    void deleteByEvaluationId(Long evaluationId);

    /** Nota média por item de ficha de um agente num período — piores itens do relatório
     * de performance (V39). Retorna {@code [itemId, pergunta, mediaNota]}. */
    @Query("SELECT si.id, si.pergunta, AVG(cei.nota) FROM CallEvaluationItem cei " +
           "JOIN CallEvaluation ce ON ce.id = cei.evaluationId " +
           "JOIN CallAudioFile caf ON caf.id = ce.audioFileId " +
           "JOIN ScorecardItem si ON si.id = cei.itemId " +
           "WHERE caf.agentName = :agentName AND caf.callStarttime BETWEEN :from AND :to " +
           "GROUP BY si.id, si.pergunta")
    List<Object[]> averageNotaByItemForAgentPeriod(@Param("agentName") String agentName,
                                                    @Param("from") java.time.LocalDateTime from,
                                                    @Param("to") java.time.LocalDateTime to);
}
