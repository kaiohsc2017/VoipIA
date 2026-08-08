package com.asteriskia.domain.callcenter.reports;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcAggQueueDailyRepository extends JpaRepository<CcAggQueueDaily, Long> {

    /** Upsert via find-then-save (JPA puro, sem SQL nativo) — volume é baixo (no máximo
     * filas x dias reprocessados por vez), não justifica a complexidade de um
     * INSERT ... ON CONFLICT nativo aqui. */
    Optional<CcAggQueueDaily> findByQueueIdAndDate(Long queueId, LocalDate date);

    List<CcAggQueueDaily> findByQueueIdAndDateBetweenOrderByDateAsc(Long queueId, LocalDate from, LocalDate to);

    List<CcAggQueueDaily> findByDateBetweenOrderByQueueIdAscDateAsc(LocalDate from, LocalDate to);
}
