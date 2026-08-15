package com.asteriskia.domain.callcenter.reports;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcAggChatDailyRepository extends JpaRepository<CcAggChatDaily, Long> {
    Optional<CcAggChatDaily> findByQueueIdAndDate(Long queueId, LocalDate date);

    List<CcAggChatDaily> findByQueueIdAndDateBetweenOrderByDateAsc(Long queueId, LocalDate from, LocalDate to);

    List<CcAggChatDaily> findByDateBetweenOrderByQueueIdAscDateAsc(LocalDate from, LocalDate to);
}
