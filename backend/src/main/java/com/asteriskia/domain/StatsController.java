package com.asteriskia.domain;

import com.asteriskia.domain.alert.AlertCall;
import com.asteriskia.domain.call.CallRecord;
import com.asteriskia.domain.connectivity.TestResult;
import com.asteriskia.domain.masterdata.BusinessUnitContext;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Repository;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
            @RequestParam(defaultValue = "week") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {

        // Intervalo customizado tem prioridade sobre o período nomeado (week/month) —
        // mesmo padrão já usado em /calls/ranking.
        LocalDateTime start, end;
        if (dateFrom != null && dateTo != null) {
            start = LocalDateTime.of(dateFrom, LocalTime.MIN);
            end = LocalDateTime.of(dateTo, LocalTime.MAX);
        } else {
            int days = "month".equals(period) ? 30 : 7;
            end = LocalDateTime.now();
            start = end.minusDays(days).truncatedTo(ChronoUnit.DAYS);
        }

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
    // Módulo 1 — URA Ranking de Atendimentos (clientes, tipo, soluções Jira)
    // -----------------------------------------------------------------------

    @GetMapping("/calls/ranking")
    public ResponseEntity<Map<String, Object>> callsRanking(
            @RequestParam(defaultValue = "week") String period,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) Integer uraId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {

        // Limita o top-N a uma faixa razoável — evita query sem cap por má configuração do client.
        int safeLimit = Math.min(Math.max(limit, 1), 50);

        // Intervalo customizado (dateFrom/dateTo) tem prioridade sobre o período nomeado
        // (today/week/month/all) — mesmo padrão de ReportController (month vs dateFrom/dateTo).
        LocalDateTime[] range = (dateFrom != null && dateTo != null)
                ? new LocalDateTime[]{LocalDateTime.of(dateFrom, LocalTime.MIN), LocalDateTime.of(dateTo, LocalTime.MAX)}
                : getRange(period);
        LocalDateTime from = range[0], to = range[1];

        // Escopo por BU — mesmo padrão de CallRecordSpecifications.restrictedToBusinessUnits.
        // Sentinela {-1} evita IN () vazio quando o usuário é restrito mas não tem BU nenhuma.
        boolean restricted = BusinessUnitContext.isRestricted();
        Set<Integer> buIds = BusinessUnitContext.currentBusinessUnitIds();
        Set<Integer> safeBuIds = buIds.isEmpty() ? Set.of(-1) : buIds;

        List<Map<String, Object>> topClients = toRankingList(callRepo.topClients(from, to, safeLimit, restricted, safeBuIds, uraId));
        List<Map<String, Object>> byType = toRankingList(callRepo.byCallType(from, to, restricted, safeBuIds, uraId));
        List<Map<String, Object>> topResolutions = toRankingList(callRepo.topResolutions(from, to, safeLimit, restricted, safeBuIds, uraId));

        // Assunto mais pedido por tipo de chamada (subject_tag, classificado por IA) —
        // um card por tipo real observado no período, não uma lista fixa Incidente/Requisição,
        // já que o "tipo" hoje vem de texto livre da URA (ver call_type).
        Map<String, List<Map<String, Object>>> topSubjectsByType = new LinkedHashMap<>();
        for (Map<String, Object> typeEntry : byType) {
            String callType = String.valueOf(typeEntry.get("label"));
            topSubjectsByType.put(callType,
                    toRankingList(callRepo.topSubjectsByCallType(from, to, callType, safeLimit, restricted, safeBuIds, uraId)));
        }

        List<Map<String, Object>> avgDurationByType = toAvgDurationList(callRepo.avgDurationByCallType(from, to, restricted, safeBuIds, uraId));

        Map<String, Object> trend = buildRankingTrend(range, safeLimit, restricted, safeBuIds, uraId,
                topClients, byType, topResolutions, topSubjectsByType, avgDurationByType);

        Map<String, Object> result = new HashMap<>();
        result.put("period", period);
        result.put("topClients", topClients);
        result.put("byType", byType);
        result.put("topResolutions", topResolutions);
        result.put("topSubjectsByType", topSubjectsByType);
        result.put("avgDurationByType", avgDurationByType);
        result.put("trend", trend);

        return ResponseEntity.ok(result);
    }

    /**
     * Tendência período-a-período: reexecuta as mesmas queries na janela imediatamente
     * anterior (mesma duração de [from,to]) e devolve só os totais agregados — o
     * suficiente para o frontend mostrar "▲/▼ X% vs. período anterior" em cada card,
     * sem duplicar a lógica de limites de período no cliente.
     */
    private Map<String, Object> buildRankingTrend(
            LocalDateTime[] range, int safeLimit, boolean restricted, Set<Integer> safeBuIds, Integer uraId,
            List<Map<String, Object>> topClients, List<Map<String, Object>> byType,
            List<Map<String, Object>> topResolutions, Map<String, List<Map<String, Object>>> topSubjectsByType,
            List<Map<String, Object>> avgDurationByType) {

        LocalDateTime[] prevRange = PeriodRangeResolver.previous(range);
        LocalDateTime prevFrom = prevRange[0], prevTo = prevRange[1];

        List<Map<String, Object>> prevTopClients = toRankingList(callRepo.topClients(prevFrom, prevTo, safeLimit, restricted, safeBuIds, uraId));
        List<Map<String, Object>> prevByType = toRankingList(callRepo.byCallType(prevFrom, prevTo, restricted, safeBuIds, uraId));
        List<Map<String, Object>> prevTopResolutions = toRankingList(callRepo.topResolutions(prevFrom, prevTo, safeLimit, restricted, safeBuIds, uraId));
        List<Map<String, Object>> prevAvgDurationByType = toAvgDurationList(callRepo.avgDurationByCallType(prevFrom, prevTo, restricted, safeBuIds, uraId));

        Map<String, Long> subjectsTotalByType = new LinkedHashMap<>();
        Map<String, Long> subjectsPrevTotalByType = new LinkedHashMap<>();
        for (String callType : topSubjectsByType.keySet()) {
            subjectsTotalByType.put(callType, sumTotal(topSubjectsByType.get(callType)));
            subjectsPrevTotalByType.put(callType,
                    sumTotal(toRankingList(callRepo.topSubjectsByCallType(prevFrom, prevTo, callType, safeLimit, restricted, safeBuIds, uraId))));
        }

        Map<String, Object> trend = new HashMap<>();
        trend.put("topClientsTotal", sumTotal(topClients));
        trend.put("topClientsPrevTotal", sumTotal(prevTopClients));
        trend.put("byTypeTotal", sumTotal(byType));
        trend.put("byTypePrevTotal", sumTotal(prevByType));
        trend.put("topResolutionsTotal", sumTotal(topResolutions));
        trend.put("topResolutionsPrevTotal", sumTotal(prevTopResolutions));
        trend.put("avgDurationSecs", avgOfAvgDurations(avgDurationByType));
        trend.put("avgDurationPrevSecs", avgOfAvgDurations(prevAvgDurationByType));
        trend.put("subjectsTotalByType", subjectsTotalByType);
        trend.put("subjectsPrevTotalByType", subjectsPrevTotalByType);
        return trend;
    }

    private long sumTotal(List<Map<String, Object>> items) {
        return items.stream().mapToLong(i -> ((Number) i.get("total")).longValue()).sum();
    }

    private double avgOfAvgDurations(List<Map<String, Object>> items) {
        return items.stream().mapToDouble(i -> ((Number) i.get("avgDurationSecs")).doubleValue())
                .average().orElse(0.0);
    }

    private List<Map<String, Object>> toRankingList(List<Object[]> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("label", row[0]);
            item.put("total", ((Number) row[1]).longValue());
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> toAvgDurationList(List<Object[]> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("label", row[0]);
            item.put("avgDurationSecs", Math.round(((Number) row[1]).doubleValue() * 10.0) / 10.0);
            result.add(item);
        }
        return result;
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
        return PeriodRangeResolver.resolve(period);
    }
}
