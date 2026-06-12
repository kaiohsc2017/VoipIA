package com.asteriskia.domain.call;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * CallRecordRepository — Acesso a dados dos registros de chamada.
 */
@Repository
public interface CallRecordRepository extends JpaRepository<CallRecord, Long> {

    Optional<CallRecord> findByCallUuid(UUID callUuid);

    Page<CallRecord> findAllByOrderByCallDateDesc(Pageable pageable);

    Page<CallRecord> findByCallerNumberContainingOrderByCallDateDesc(String callerNumber, Pageable pageable);

    // --- Métodos de exportação (Fase 11) ---

    /** Todos os registros para exportação CSV (sem paginar). */
    List<CallRecord> findAllByOrderByCallDateDesc();

    /** Registros filtrados por período para exportação CSV. */
    List<CallRecord> findByCallDateBetweenOrderByCallDateDesc(LocalDateTime from, LocalDateTime to);
}
