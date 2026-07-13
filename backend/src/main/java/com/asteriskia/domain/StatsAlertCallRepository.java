package com.asteriskia.domain;

import com.asteriskia.domain.alert.AlertCall;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface StatsAlertCallRepository extends JpaRepository<AlertCall, Long> {
    @Query("SELECT COUNT(a) FROM AlertCall a WHERE a.callDate BETWEEN :from AND :to")
    long countByPeriod(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query(
            "SELECT COUNT(a) FROM AlertCall a WHERE a.callStatus = :status AND a.callDate BETWEEN :from AND :to")
    long countByStatusAndPeriod(
            @Param("status") String status,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Query(
            value =
                    "SELECT COUNT(*) FROM alert_calls a WHERE a.telegram_sent_at IS NOT NULL "
                            + "AND a.call_date BETWEEN :from AND :to",
            nativeQuery = true)
    long countTelegramSentByPeriod(
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
