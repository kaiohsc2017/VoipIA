package com.asteriskia.domain.report;

import com.asteriskia.domain.PeriodRangeResolver;
import com.asteriskia.domain.StatsCallRepository;
import com.asteriskia.domain.call.CallRecord;
import com.asteriskia.domain.call.CallRecordRepository;
import com.asteriskia.domain.connectivity.ConnectivityReportRepository;
import com.asteriskia.domain.connectivity.TestResult;
import com.asteriskia.domain.masterdata.BusinessUnitContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * ReportController — Fase 11: Exportação de relatórios em CSV. A montagem/escaping dos CSVs e o
 * envelope de download HTTP vivem em {@link ReportCsvBuilder} (extraído na fase 13 da refatoração).
 *
 * <p>GET /api/v1/reports/connectivity → CSV com resultados de testes de conectividade
 * ?month=YYYY-MM (opcional) ?dateFrom=...&dateTo=... (opcional, sobrescreve month)
 * ?businessUnitId=N (opcional) ?clientId=N (opcional) ?operationId=N (opcional) ?segmentId=N
 * (opcional) ?status=... (opcional)
 *
 * <p>GET /api/v1/reports/connectivity/summary → JSON com totais por BU e Cliente ?month=YYYY-MM
 *
 * <p>GET /api/v1/reports/ura → CSV com histórico de chamadas URA ?month=YYYY-MM (opcional)
 * ?dateFrom=...&dateTo=... (opcional)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ConnectivityReportRepository connectivityReportRepository;
    private final CallRecordRepository callRecordRepository;
    private final StatsCallRepository statsCallRepository;

    private static final DateTimeFormatter FILE_DT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    // -------------------------------------------------------------------------
    // Conectividade — CSV
    // -------------------------------------------------------------------------

    @GetMapping("/connectivity")
    public ResponseEntity<byte[]> exportConnectivity(
            @RequestParam(required = false) String month,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime dateTo,
            @RequestParam(required = false) Long businessUnitId,
            @RequestParam(required = false) Long clientId,
            @RequestParam(required = false) Long operationId,
            @RequestParam(required = false) Long segmentId,
            @RequestParam(required = false) String status) {

        // Resolve período
        if (dateFrom == null && dateTo == null && month != null) {
            YearMonth ym = YearMonth.parse(month);
            dateFrom = ym.atDay(1).atStartOfDay();
            dateTo = ym.atEndOfMonth().atTime(23, 59, 59);
        }

        log.info(
                "Exportando conectividade: from={}, to={}, bu={}, client={}, op={}, seg={}, status={}",
                dateFrom,
                dateTo,
                businessUnitId,
                clientId,
                operationId,
                segmentId,
                status);

        List<TestResult> results =
                connectivityReportRepository.findForExport(
                        status, dateFrom, dateTo, businessUnitId, clientId, operationId, segmentId);

        String csv = ReportCsvBuilder.buildConnectivityCsv(results);
        String filename = "conectividade_" + LocalDateTime.now().format(FILE_DT) + ".csv";

        return ReportCsvBuilder.csvResponse(csv, filename);
    }

    // -------------------------------------------------------------------------
    // Conectividade — Sumário JSON (por BU e Cliente)
    // -------------------------------------------------------------------------

    @GetMapping("/connectivity/summary")
    public ResponseEntity<List<ConnectivitySummaryDTO>> connectivitySummary(
            @RequestParam(defaultValue = "") String month) {

        LocalDateTime from, to;
        if (month.isEmpty()) {
            from = LocalDateTime.now().withDayOfMonth(1).toLocalDate().atStartOfDay();
            to = LocalDateTime.now();
        } else {
            YearMonth ym = YearMonth.parse(month);
            from = ym.atDay(1).atStartOfDay();
            to = ym.atEndOfMonth().atTime(23, 59, 59);
        }

        List<TestResult> results =
                connectivityReportRepository.findForExport(null, from, to, null, null, null, null);

        // Agrupa por BU + Cliente
        Map<String, ConnectivitySummaryDTO> grouped = new LinkedHashMap<>();
        for (TestResult r : results) {
            if (r.getNumberTest() == null) continue;
            String buName =
                    r.getNumberTest().getBusinessUnit() != null
                            ? r.getNumberTest().getBusinessUnit().getName()
                            : "—";
            String cliName =
                    r.getNumberTest().getClient() != null
                            ? r.getNumberTest().getClient().getName()
                            : "—";
            String key = buName + "|" + cliName;
            ConnectivitySummaryDTO dto =
                    grouped.computeIfAbsent(
                            key, k -> new ConnectivitySummaryDTO(buName, cliName, 0, 0, 0));
            dto.total++;
            if ("SUCESSO".equals(r.getStatus())) dto.sucesso++;
            else dto.falha++;
        }

        List<ConnectivitySummaryDTO> summary = new ArrayList<>(grouped.values());
        summary.forEach(
                s -> s.taxaSucesso = s.total > 0 ? Math.round(s.sucesso * 100.0 / s.total) : 0);

        return ResponseEntity.ok(summary);
    }

    // -------------------------------------------------------------------------
    // URA — CSV de chamadas
    // -------------------------------------------------------------------------

    @GetMapping("/ura")
    public ResponseEntity<byte[]> exportUra(
            @RequestParam(required = false) String month,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime dateTo) {

        if (dateFrom == null && dateTo == null && month != null) {
            YearMonth ym = YearMonth.parse(month);
            dateFrom = ym.atDay(1).atStartOfDay();
            dateTo = ym.atEndOfMonth().atTime(23, 59, 59);
        }

        log.info("Exportando URA: from={}, to={}", dateFrom, dateTo);

        List<CallRecord> calls =
                (dateFrom != null && dateTo != null)
                        ? callRecordRepository.findByCallDateBetweenOrderByCallDateDesc(
                                dateFrom, dateTo)
                        : callRecordRepository.findAllByOrderByCallDateDesc();

        String csv = ReportCsvBuilder.buildUraCsv(calls);
        String filename = "chamadas_ura_" + LocalDateTime.now().format(FILE_DT) + ".csv";

        return ReportCsvBuilder.csvResponse(csv, filename);
    }

    // -------------------------------------------------------------------------
    // Ranking de Atendimentos — CSV por indicador (item 5 do backlog de melhorias)
    // -------------------------------------------------------------------------

    /**
     * Exporta um indicador da aba Ranking de Atendimentos em CSV.
     *
     * @param section topClients | byType | topResolutions | topSubjectsByType | avgDurationByType
     * @param callType obrigatório apenas quando section=topSubjectsByType (mesmo call_type do card
     *     na tela)
     */
    @GetMapping("/ranking")
    public ResponseEntity<byte[]> exportRanking(
            @RequestParam(defaultValue = "all") String period,
            @RequestParam String section,
            @RequestParam(required = false) String callType,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) Integer uraId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate dateTo) {

        int safeLimit = Math.min(Math.max(limit, 1), 50);
        LocalDateTime[] range =
                (dateFrom != null && dateTo != null)
                        ? new LocalDateTime[] {
                            LocalDateTime.of(dateFrom, LocalTime.MIN),
                            LocalDateTime.of(dateTo, LocalTime.MAX)
                        }
                        : PeriodRangeResolver.resolve(period);
        LocalDateTime from = range[0], to = range[1];

        boolean restricted = BusinessUnitContext.isRestricted();
        Set<Integer> buIds = BusinessUnitContext.currentBusinessUnitIds();
        Set<Integer> safeBuIds = buIds.isEmpty() ? Set.of(-1) : buIds;

        String header;
        List<Object[]> rows;
        switch (section) {
            case "topClients" -> {
                header = "Cliente,Chamadas";
                rows =
                        statsCallRepository.topClients(
                                from, to, safeLimit, restricted, safeBuIds, uraId);
            }
            case "byType" -> {
                header = "Tipo,Chamadas";
                rows = statsCallRepository.byCallType(from, to, restricted, safeBuIds, uraId);
            }
            case "topResolutions" -> {
                header = "Solução (Jira),Chamadas";
                rows =
                        statsCallRepository.topResolutions(
                                from, to, safeLimit, restricted, safeBuIds, uraId);
            }
            case "topSubjectsByType" -> {
                if (callType == null || callType.isBlank()) {
                    return ResponseEntity.badRequest().build();
                }
                header = "Assunto,Chamadas";
                rows =
                        statsCallRepository.topSubjectsByCallType(
                                from, to, callType, safeLimit, restricted, safeBuIds, uraId);
            }
            case "avgDurationByType" -> {
                header = "Tipo,Duração Média (s)";
                rows =
                        statsCallRepository.avgDurationByCallType(
                                from, to, restricted, safeBuIds, uraId);
            }
            default -> {
                return ResponseEntity.badRequest().build();
            }
        }

        String csv = ReportCsvBuilder.buildLabelValueCsv(header, rows);
        String filename = "ranking_" + section + "_" + LocalDateTime.now().format(FILE_DT) + ".csv";
        return ReportCsvBuilder.csvResponse(csv, filename);
    }
}
