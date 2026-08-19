package com.asteriskia.domain.callcenter.wfm;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CcQueueWfmForecastRepository extends JpaRepository<CcQueueWfmForecast, Long> {

    List<CcQueueWfmForecast> findByQueueIdOrderByForecastTimestampDesc(Long queueId);

    @Query("SELECT f FROM CcQueueWfmForecast f JOIN FETCH f.queue WHERE f.queue.id = :queueId AND f.forecastTimestamp >= :since ORDER BY f.forecastTimestamp ASC")
    List<CcQueueWfmForecast> findRecentByQueueId(@Param("queueId") Long queueId, @Param("since") Instant since);

    @Query("SELECT f FROM CcQueueWfmForecast f JOIN FETCH f.queue WHERE f.slaBreachRisk = true AND f.forecastTimestamp >= :since ORDER BY f.forecastTimestamp ASC")
    List<CcQueueWfmForecast> findActiveBreachRisks(@Param("since") Instant since);
}
