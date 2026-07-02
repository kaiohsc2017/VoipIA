package com.asteriskia.domain;

import com.asteriskia.domain.alert.AlertCall;
import com.asteriskia.domain.call.CallRecord;
import com.asteriskia.domain.connectivity.TestResult;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Repository;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * StatsController — KPIs agregados por módulo para os dashboards (Fases 7).
 *
 * GET /api/v1/stats/connectivity?period=today|week|month
 * GET /api/v1/stats/calls?period=today|week|month
 * GET /api/v1/stats/alerts?period=today|week|month
 * GET /api/v1/stats/trunk-status  → status do tronco SIP via qualify AMI
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/stats")
@RequiredArgsConstructor
public class StatsController {

    @Value("${app.asterisk.ami.host:asterisk}")
    private String amiHost;

    @Value("${app.asterisk.ami.port:5038}")
    private int amiPort;

    @Value("${app.asterisk.ami.user:asteriskia}")
    private String amiUser;

    @Value("${app.asterisk.ami.password}")
    private String amiPassword;

    private static final int AMI_TIMEOUT_MS = 8_000;

    private final StatsCallRepository callRepo;
    private final StatsTestResultRepository testResultRepo;
    private final StatsAlertCallRepository alertCallRepo;
    private final StatsNumberTestRepository numberTestRepo;

    // -----------------------------------------------------------------------
    // Módulo 2 — Conectividade (7 KPIs)
    // -----------------------------------------------------------------------

    @GetMapping("/connectivity")
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
    // Módulo 1 — URA Timeseries (gráfico temporal)
    // -----------------------------------------------------------------------

    @GetMapping("/calls/timeseries")
    public ResponseEntity<List<Map<String, Object>>> callsTimeseries(
            @RequestParam(defaultValue = "week") String period) {

        int days = "month".equals(period) ? 30 : 7;
        LocalDateTime end = LocalDateTime.now();
        LocalDateTime start = end.minusDays(days).truncatedTo(ChronoUnit.DAYS);

        List<Object[]> raw = callRepo.countByDay(start, end);
        List<Map<String, Object>> result = new ArrayList<>();

        for (Object[] row : raw) {
            Map<String, Object> point = new HashMap<>();
            point.put("date", row[0] != null ? row[0].toString() : "");
            point.put("total", row[1]);
            point.put("jiraOpened", row[2]);
            point.put("avgDuration", row[3] != null ? Math.round(((Number) row[3]).doubleValue()) : 0);
            result.add(point);
        }
        return ResponseEntity.ok(result);
    }

    // -----------------------------------------------------------------------
    // Módulo 3 — Alertas Zabbix
    // -----------------------------------------------------------------------

    @GetMapping("/alerts")
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
    // Tronco SIP — status via qualify AMI
    // -----------------------------------------------------------------------

    @GetMapping("/trunk-status")
    public ResponseEntity<Map<String, Object>> trunkStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checkedAt", Instant.now().toString());
        try (Socket s = new Socket(amiHost, amiPort)) {
            s.setSoTimeout(AMI_TIMEOUT_MS);
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter    w = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
            r.readLine(); // banner

            sendAmiBlock(w, "Action", "Login", "Username", amiUser, "Secret", amiPassword);
            if (!readAmiBlock(r).contains("Success")) {
                result.put("status", "UNKNOWN"); result.put("rttMs", -1); result.put("error", "ami_auth");
                return ResponseEntity.ok(result);
            }

            sendAmiBlock(w, "Action", "Command", "Command", "pjsip show contacts");
            // O AMI envia Command em dois blocos: cabeçalho "Response: Success" + linhas "Output:".
            // Lemos diretamente até encontrar a linha terminadora do pjsip show contacts.
            String contacts = readCommandOutput(r);
            sendAmiBlock(w, "Action", "Logoff");

            result.put("status", "UNKNOWN");
            result.put("rttMs", -1);
            for (String line : contacts.split("\n")) {
                if (!line.contains("tronco-sip")) continue;
                if (line.contains("Avail") && !line.contains("Unavail")) {
                    result.put("status", "ONLINE");
                    result.put("rttMs", parseRttMs(line));
                } else {
                    result.put("status", "OFFLINE");
                    result.put("rttMs", -1);
                }
                break;
            }
        } catch (Exception e) {
            log.warn("trunk-status AMI error: {}", e.getMessage());
            result.put("status", "UNKNOWN"); result.put("rttMs", -1); result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    private int parseRttMs(String line) {
        String[] parts = line.trim().split("\\s+");
        try { return (int) Double.parseDouble(parts[parts.length - 1]); }
        catch (NumberFormatException e) { return -1; }
    }

    private void sendAmiBlock(PrintWriter w, String... kv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < kv.length - 1; i += 2)
            sb.append(kv[i]).append(": ").append(kv[i + 1]).append("\r\n");
        sb.append("\r\n"); w.print(sb); w.flush();
    }

    private String readAmiBlock(BufferedReader r) throws IOException {
        StringBuilder sb = new StringBuilder(); String line;
        while ((line = r.readLine()) != null) { if (line.isEmpty()) break; sb.append(line).append("\n"); }
        return sb.toString();
    }

    /** Lê linhas do AMI até encontrar a sentinela de fim do 'pjsip show contacts'. */
    private String readCommandOutput(BufferedReader r) throws IOException {
        StringBuilder sb = new StringBuilder(); String line;
        while ((line = r.readLine()) != null) {
            sb.append(line).append("\n");
            if (line.startsWith("Output: Objects found:") || line.contains("--END COMMAND--")) break;
        }
        return sb.toString();
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
