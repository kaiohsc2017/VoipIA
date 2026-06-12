package com.asteriskia.domain;

import com.asteriskia.domain.alert.AlertCall;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface StatsAlertCallRepository extends JpaRepository<AlertCall, Long> {
    @Query("SELECT COUNT(a) FROM AlertCall a WHERE a.callDate BETWEEN :from AND :to")
    long countByPeriod(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(a) FROM AlertCall a WHERE a.callStatus = :status AND a.callDate BETWEEN :from AND :to")
    long countByStatusAndPeriod(@Param("status") String status, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(a) FROM AlertCall a WHERE a.telegramSentAt IS NOT NULL AND a.callDate BETWEEN :from AND :to")
    long countTelegramSentByPeriod(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
