package com.asteriskia.domain;

import com.asteriskia.domain.alert.AlertCall;
import com.asteriskia.domain.call.CallRecord;
import com.asteriskia.domain.connectivity.TestResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Repository;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

/**
 * StatsController — KPIs agregados por módulo para os dashboards (Fases 7).
 *
 * GET /api/v1/stats/connectivity?period=today|week|month
 * GET /api/v1/stats/calls?period=today|week|month
 * GET /api/v1/stats/alerts?period=today|week|month
 */
@RestController
@RequestMapping("/api/v1/stats")
@RequiredArgsConstructor
@Tag(name = "Statistics", description = "KPIs e estatísticas para dashboards por módulo")
public class StatsController {

    private final StatsCallRepository callRepo;
    private final StatsTestResultRepository testResultRepo;
    private final StatsAlertCallRepository alertCallRepo;
    private final StatsNumberTestRepository numberTestRepo;

    // -----------------------------------------------------------------------
    // Módulo 2 — Conectividade (7 KPIs)
    // -----------------------------------------------------------------------

    @GetMapping("/connectivity")
    @Operation(summary = "KPIs do Módulo 2 — Testes de Conectividade")
    public ResponseEntity<Map<String, Object>> connectivityStats(
            @RequestParam(defaultValue = "today") String period) {

        LocalDateTime[] range = getRange(period);
        LocalDateTime from = range[0], to = range[1];

        long totalTests = testResultRepo.countByPeriod(from, to);
        long successTests = testResultRepo.countByStatusAndPeriod("SUCESSO", from, to);
        long failedTests = testResultRepo.countByStatusAndPeriod("FALHA", from, to);
        long scheduledCount = numberTestRepo.countByIsActiveTrue();

        // Período semana também para comparação
        LocalDateTime[] weekRange = getRange("week");
        long totalWeek = testResultRepo.countByPeriod(weekRange[0], weekRange[1]);
        long successWeek = testResultRepo.countByStatusAndPeriod("SUCESSO", weekRange[0], weekRange[1]);
        long failedWeek = testResultRepo.countByStatusAndPeriod("FALHA", weekRange[0], weekRange[1]);

        double successRate = totalTests > 0 ? Math.round((successTests * 100.0 / totalTests) * 10.0) / 10.0 : 0;
        double failRate = totalTests > 0 ? Math.round((failedTests * 100.0 / totalTests) * 10.0) / 10.0 : 0;
        double completionRate = scheduledCount > 0 ? Math.round((totalTests * 100.0 / Math.max(scheduledCount, totalTests)) * 10.0) / 10.0 : 0;

        Map<String, Object> stats = new HashMap<>();
        stats.put("period", period);
        // KPIs dia/semana
        stats.put("totalTestsToday", totalTests);
        stats.put("successesToday", successTests);
        stats.put("failuresToday", failedTests);
        stats.put("totalTestsWeek", totalWeek);
        stats.put("successesWeek", successWeek);
        stats.put("failuresWeek", failedWeek);
        // Percentuais
        stats.put("successRatePct", successRate);
        stats.put("failRatePct", failRate);
        stats.put("completionRatePct", completionRate);
        stats.put("pendingPct", Math.max(0, 100.0 - completionRate));
        // Totais
        stats.put("scheduledCount", scheduledCount);

        return ResponseEntity.ok(stats);
    }

    // -----------------------------------------------------------------------
    // Módulo 1 — URA / Jira
    // -----------------------------------------------------------------------

    @GetMapping("/calls")
    @Operation(summary = "KPIs do Módulo 1 — URA e Jira")
    public ResponseEntity<Map<String, Object>> callStats(
            @RequestParam(defaultValue = "today") String period) {

        LocalDateTime[] range = getRange(period);
        LocalDateTime from = range[0], to = range[1];

        long totalCalls = callRepo.countByPeriod(from, to);
        long callsWithJira = callRepo.countWithJiraByPeriod(from, to);
        long callsWithTranscription = callRepo.countWithTranscriptionByPeriod(from, to);
        double avgDuration = callRepo.avgDurationByPeriod(from, to);
        double jiraRate = totalCalls > 0 ? Math.round((callsWithJira * 100.0 / totalCalls) * 10.0) / 10.0 : 0;

        Map<String, Object> stats = new HashMap<>();
        stats.put("period", period);
        stats.put("totalCalls", totalCalls);
        stats.put("callsWithJira", callsWithJira);
        stats.put("callsWithTranscription", callsWithTranscription);
        stats.put("jiraSuccessRatePct", jiraRate);
        stats.put("avgDurationSecs", avgDuration > 0 ? Math.round(avgDuration) : 0);

        return ResponseEntity.ok(stats);
    }

    // -----------------------------------------------------------------------
    // Módulo 3 — Alertas Zabbix
    // -----------------------------------------------------------------------

    @GetMapping("/alerts")
    @Operation(summary = "KPIs do Módulo 3 — Alertas Zabbix")
    public ResponseEntity<Map<String, Object>> alertStats(
            @RequestParam(defaultValue = "today") String period) {

        LocalDateTime[] range = getRange(period);
        LocalDateTime from = range[0], to = range[1];

        long totalAlerts = alertCallRepo.countByPeriod(from, to);
        long answered = alertCallRepo.countByStatusAndPeriod("ATENDIDA", from, to);
        long notAnswered = alertCallRepo.countByStatusAndPeriod("NAO_ATENDIDA", from, to);
        long failed = alertCallRepo.countByStatusAndPeriod("FALHA", from, to);
        long telegramSent = alertCallRepo.countTelegramSentByPeriod(from, to);

        double answeredRate = totalAlerts > 0 ? Math.round((answered * 100.0 / totalAlerts) * 10.0) / 10.0 : 0;
        double telegramRate = totalAlerts > 0 ? Math.round((telegramSent * 100.0 / totalAlerts) * 10.0) / 10.0 : 0;

        Map<String, Object> stats = new HashMap<>();
        stats.put("period", period);
        stats.put("totalAlerts", totalAlerts);
        stats.put("answered", answered);
        stats.put("notAnswered", notAnswered);
        stats.put("failed", failed);
        stats.put("telegramSent", telegramSent);
        stats.put("answeredRatePct", answeredRate);
        stats.put("telegramSuccessRatePct", telegramRate);

        return ResponseEntity.ok(stats);
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private LocalDateTime[] getRange(String period) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime from = switch (period) {
            case "week" -> now.truncatedTo(ChronoUnit.DAYS).minusDays(now.getDayOfWeek().getValue() - 1);
            case "month" -> now.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
            default -> now.truncatedTo(ChronoUnit.DAYS); // today
        };
        return new LocalDateTime[]{from, now};
    }
}

// ---------------------------------------------------------------------------
// Repositories de stats (queries de agregação)
// ---------------------------------------------------------------------------

@Repository
interface StatsCallRepository extends JpaRepository<CallRecord, Long> {
    @Query("SELECT COUNT(c) FROM CallRecord c WHERE c.callDate BETWEEN :from AND :to")
    long countByPeriod(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(c) FROM CallRecord c WHERE c.jiraIssueKey IS NOT NULL AND c.callDate BETWEEN :from AND :to")
    long countWithJiraByPeriod(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(c) FROM CallRecord c WHERE c.transcription IS NOT NULL AND c.callDate BETWEEN :from AND :to")
    long countWithTranscriptionByPeriod(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COALESCE(AVG(c.callDurationSecs), 0) FROM CallRecord c WHERE c.callDate BETWEEN :from AND :to")
    double avgDurationByPeriod(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}

@Repository
interface StatsTestResultRepository extends JpaRepository<TestResult, Long> {
    @Query("SELECT COUNT(r) FROM TestResult r WHERE r.executedAt BETWEEN :from AND :to")
    long countByPeriod(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(r) FROM TestResult r WHERE r.status = :status AND r.executedAt BETWEEN :from AND :to")
    long countByStatusAndPeriod(@Param("status") String status, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}

@Repository
interface StatsAlertCallRepository extends JpaRepository<AlertCall, Long> {
    @Query("SELECT COUNT(a) FROM AlertCall a WHERE a.callDate BETWEEN :from AND :to")
    long countByPeriod(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(a) FROM AlertCall a WHERE a.callStatus = :status AND a.callDate BETWEEN :from AND :to")
    long countByStatusAndPeriod(@Param("status") String status, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(a) FROM AlertCall a WHERE a.telegramSentAt IS NOT NULL AND a.callDate BETWEEN :from AND :to")
    long countTelegramSentByPeriod(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}

@Repository
interface StatsNumberTestRepository extends JpaRepository<com.asteriskia.domain.connectivity.NumberTest, Long> {
    long countByIsActiveTrue();
}
