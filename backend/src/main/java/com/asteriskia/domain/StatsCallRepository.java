package com.asteriskia.domain;

import com.asteriskia.domain.alert.AlertCall;
import com.asteriskia.domain.call.CallRecord;
import com.asteriskia.domain.connectivity.NumberTest;
import com.asteriskia.domain.connectivity.TestResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface StatsCallRepository extends JpaRepository<CallRecord, Long> {
    @Query("SELECT COUNT(c) FROM CallRecord c WHERE c.callDate BETWEEN :from AND :to")
    long countByPeriod(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(c) FROM CallRecord c WHERE c.jiraIssueKey IS NOT NULL AND c.callDate BETWEEN :from AND :to")
    long countWithJiraByPeriod(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(c) FROM CallRecord c WHERE c.transcription IS NOT NULL AND c.callDate BETWEEN :from AND :to")
    long countWithTranscriptionByPeriod(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COALESCE(AVG(c.callDurationSecs), 0) FROM CallRecord c WHERE c.callDate BETWEEN :from AND :to")
    double avgDurationByPeriod(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT CAST(c.callDate AS date) as day, COUNT(c), " +
           "SUM(CASE WHEN c.jiraIssueKey IS NOT NULL THEN 1 ELSE 0 END), " +
           "AVG(c.callDurationSecs) " +
           "FROM CallRecord c WHERE c.callDate BETWEEN :from AND :to " +
           "GROUP BY CAST(c.callDate AS date) ORDER BY day")
    List<Object[]> countByDay(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
