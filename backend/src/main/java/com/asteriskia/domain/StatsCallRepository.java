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

    // nativeQuery=true evita o erro "could not determine data type of parameter $N"
    // que o Hibernate 6 gera com IS NOT NULL em JPQL sobre colunas TEXT nullable no PostgreSQL
    @Query(value = "SELECT COUNT(*) FROM call_records c WHERE c.jira_issue_key IS NOT NULL AND c.call_date BETWEEN :from AND :to", nativeQuery = true)
    long countWithJiraByPeriod(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query(value = "SELECT COUNT(*) FROM call_records c WHERE c.transcription IS NOT NULL AND c.call_date BETWEEN :from AND :to", nativeQuery = true)
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
