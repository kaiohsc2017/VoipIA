package com.asteriskia.domain.call;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * CallRecordRepository — Acesso a dados dos registros de chamada.
 */
@Repository
public interface CallRecordRepository extends JpaRepository<CallRecord, Long>, JpaSpecificationExecutor<CallRecord> {

    Optional<CallRecord> findByCallUuid(UUID callUuid);

    Page<CallRecord> findAllByOrderByCallDateDesc(Pageable pageable);

    Page<CallRecord> findByCallerNumberContainingOrderByCallDateDesc(String callerNumber, Pageable pageable);

    // --- Métodos de exportação (Fase 11) ---

    /** Todos os registros para exportação CSV (sem paginar). */
    List<CallRecord> findAllByOrderByCallDateDesc();

    /** Registros filtrados por período para exportação CSV. */
    List<CallRecord> findByCallDateBetweenOrderByCallDateDesc(LocalDateTime from, LocalDateTime to);

    /** Chamados com Jira aberto ainda sem resolução, dentro da janela de sync (JiraSyncScheduler). */
    List<CallRecord> findByJiraIssueKeyIsNotNullAndJiraResolutionIsNullAndCallDateAfter(LocalDateTime cutoff);

    /**
     * Vocabulário já usado de assuntos (subject_tag) para um call_type — consumido pelo
     * ai-agent antes de classificar uma nova chamada, para reaproveitar rótulos existentes
     * em vez de criar sinônimos novos a cada chamada.
     */
    @Query(value = "SELECT DISTINCT subject_tag FROM call_records " +
           "WHERE call_type = :callType AND subject_tag IS NOT NULL " +
           "ORDER BY subject_tag LIMIT 50", nativeQuery = true)
    List<String> findDistinctSubjectTagsByCallType(@Param("callType") String callType);
}
