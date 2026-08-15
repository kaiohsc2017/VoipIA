package com.asteriskia.domain.callcenter.reports;

import com.asteriskia.domain.callcenter.interaction.Direction;
import com.asteriskia.domain.masterdata.BusinessUnitContext;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * CallCenterReportsController — relatório analítico de fila (sub-fase 9a) e de agente (sub-fase
 * 9b) de voz, do plano modulo-callcenter-omnicanal.plan.md. RBAC via {@code callcenter.reports}
 * (mesma aba "Relatórios" para os dois sub-relatórios); {@code /reprocess} é {@code ROLE_ADMIN}
 * puro (ver SecurityConfig).
 */
@RestController
@RequestMapping("/api/v1/callcenter/reports")
@RequiredArgsConstructor
public class CallCenterReportsController {

    private final CallCenterReportsQueryService queryService;
    private final CallCenterQueueAggregationService aggregationService;
    private final CallCenterAgentAggregationService agentAggregationService;
    private final CallCenterFlowAggregationService flowAggregationService;
    private final CallCenterChatAggregationService chatAggregationService;
    private final CallCenterDetailReportService detailReportService;
    private final CallCenterTimelineService timelineService;
    private final CallCenterRecallService recallService;
    private final CallCenterReportExportService exportService;

    public record ReprocessRequest(@NotNull LocalDate from, @NotNull LocalDate to) {}

    /** {@code null} = sem restrição (ADMIN) — mesmo padrão de
     * {@code CallCenterInsightsController.businessUnitScope} (BU fechada em 2026-08-15 no
     * relatório 9c). */
    private Set<Integer> businessUnitScope() {
        return BusinessUnitContext.isRestricted() ? BusinessUnitContext.currentBusinessUnitIds() : null;
    }

    @GetMapping("/queues")
    public ResponseEntity<?> queues(
            @RequestParam(required = false) Long queueId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "DAY") String granularity) {
        CallCenterReportsQueryService.Granularity g = parseGranularity(granularity);
        if (queueId != null) {
            List<QueuePeriodMetrics> metrics = queryService.queryQueue(queueId, from, to, g);
            return ResponseEntity.ok(metrics);
        }
        Map<Long, List<QueuePeriodMetrics>> byQueue = queryService.queryAllQueues(from, to, g);
        return ResponseEntity.ok(byQueue);
    }

    @GetMapping("/queues/compare")
    public ResponseEntity<QueuePeriodComparison> compare(
            @RequestParam Long queueId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodAFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodATo,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodBFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodBTo) {
        return ResponseEntity.ok(queryService.compare(queueId, periodAFrom, periodATo, periodBFrom, periodBTo));
    }

    @GetMapping("/agents")
    public ResponseEntity<?> agents(
            @RequestParam(required = false) Long agentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "DAY") String granularity) {
        CallCenterReportsQueryService.Granularity g = parseGranularity(granularity);
        if (agentId != null) {
            List<AgentPeriodMetrics> metrics = queryService.queryAgent(agentId, from, to, g);
            return ResponseEntity.ok(metrics);
        }
        Map<Long, List<AgentPeriodMetrics>> byAgent = queryService.queryAllAgents(from, to, g);
        return ResponseEntity.ok(byAgent);
    }

    @GetMapping("/agents/compare")
    public ResponseEntity<AgentPeriodComparison> compareAgents(
            @RequestParam Long agentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodAFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodATo,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodBFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodBTo) {
        return ResponseEntity.ok(queryService.compareAgent(agentId, periodAFrom, periodATo, periodBFrom, periodBTo));
    }

    /** Agregado diário de fluxo/URA (Fase 9c.1). */
    @GetMapping("/flows")
    public ResponseEntity<?> flows(
            @RequestParam(required = false) Long flowId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "DAY") String granularity) {
        CallCenterReportsQueryService.Granularity g = parseGranularity(granularity);
        if (flowId != null) {
            List<FlowPeriodMetrics> metrics = queryService.queryFlow(flowId, from, to, g);
            return ResponseEntity.ok(metrics);
        }
        Map<Long, List<FlowPeriodMetrics>> byFlow = queryService.queryAllFlows(from, to, g);
        return ResponseEntity.ok(byFlow);
    }

    /** Abandono por nó de um fluxo, somado no período (Fase 9c.1). */
    @GetMapping("/flows/{flowId}/nodes")
    public ResponseEntity<List<FlowNodeAbandonmentRow>> flowNodes(
            @org.springframework.web.bind.annotation.PathVariable Long flowId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(queryService.queryFlowNodeAbandonment(flowId, from, to));
    }

    /** Agregado diário de chat (Fase 9c.2) — FRT/ART/concorrência/contenção do bot. Path distinto
     * de {@code /chats} (relatório detalhado linha a linha da Fase 9c) para não colidir. */
    @GetMapping("/chats/summary")
    public ResponseEntity<?> chatsSummary(
            @RequestParam(required = false) Long queueId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "DAY") String granularity) {
        CallCenterReportsQueryService.Granularity g = parseGranularity(granularity);
        if (queueId != null) {
            List<ChatPeriodMetrics> metrics = queryService.queryChat(queueId, from, to, g);
            return ResponseEntity.ok(metrics);
        }
        Map<Long, List<ChatPeriodMetrics>> byQueue = queryService.queryAllChats(from, to, g);
        return ResponseEntity.ok(byQueue);
    }

    /** Timeline omnicanal (voz+chat) de um contato, paginada em banco (Fase 9c.3) — base que a
     * Fase 16 (copiloto de IA) vai consumir. */
    @GetMapping("/timeline")
    public ResponseEntity<Page<TimelineEventRow>> timeline(
            @RequestParam String contact,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(page, clampPageSize(size));
        return ResponseEntity.ok(timelineService.timeline(contact, from, to, pageable));
    }

    /** Rechamada 24h/7d + top tabulações de uma fila (Fase 9c.4) — painel sobre o relatório de
     * fila (9a) já existente, sem tela/resource novo. */
    @GetMapping("/queues/{queueId}/recall")
    public ResponseEntity<RecallAndDispositionSummary> queueRecall(
            @org.springframework.web.bind.annotation.PathVariable Long queueId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(recallService.summarize(queueId, from, to));
    }

    /** Relatório analítico de chamada, linha a linha (Fase 9c) — fila/agente/NPS/tempo de espera/
     * opção escolhida/trecho de transcrição, todos opcionais. */
    @GetMapping("/calls")
    public ResponseEntity<Page<CallReportRow>> calls(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long queueId,
            @RequestParam(required = false) Long agentId,
            @RequestParam(required = false) Direction direction,
            @RequestParam(required = false) BigDecimal npsMin,
            @RequestParam(required = false) BigDecimal npsMax,
            @RequestParam(required = false) Long waitMinSeconds,
            @RequestParam(required = false) Long waitMaxSeconds,
            @RequestParam(required = false) String chosenOptionDigit,
            @RequestParam(required = false) String transcriptText,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        CallReportFilter filter = new CallReportFilter(
                LocalDateTime.of(from, LocalTime.MIN),
                LocalDateTime.of(to, LocalTime.MAX),
                queueId, agentId, direction, npsMin, npsMax, waitMinSeconds, waitMaxSeconds,
                chosenOptionDigit, transcriptText);
        PageRequest pageable = PageRequest.of(page, clampPageSize(size), Sort.by(Sort.Direction.DESC, "queuedAt"));
        return ResponseEntity.ok(detailReportService.searchCalls(filter, pageable, businessUnitScope()));
    }

    /** Relatório analítico de chat, linha a linha (Fase 9c) — sem NPS/trecho de transcrição (ver
     * {@link ChatReportFilter}). */
    @GetMapping("/chats")
    public ResponseEntity<Page<ChatReportRow>> chats(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long queueId,
            @RequestParam(required = false) Long agentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        ChatReportFilter filter = new ChatReportFilter(
                LocalDateTime.of(from, LocalTime.MIN), LocalDateTime.of(to, LocalTime.MAX), queueId, agentId);
        PageRequest pageable = PageRequest.of(page, clampPageSize(size), Sort.by(Sort.Direction.DESC, "startedAt"));
        return ResponseEntity.ok(detailReportService.searchChats(filter, pageable, businessUnitScope()));
    }

    /** Exportação Excel/PDF do relatório de chamada (Fase 9c.5) — mesmos filtros de {@link #calls}. */
    @GetMapping("/calls/export.xlsx")
    public ResponseEntity<byte[]> exportCallsExcel(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long queueId,
            @RequestParam(required = false) Long agentId,
            @RequestParam(required = false) Direction direction,
            @RequestParam(required = false) BigDecimal npsMin,
            @RequestParam(required = false) BigDecimal npsMax,
            @RequestParam(required = false) Long waitMinSeconds,
            @RequestParam(required = false) Long waitMaxSeconds,
            @RequestParam(required = false) String chosenOptionDigit,
            @RequestParam(required = false) String transcriptText) {
        CallReportFilter filter = buildCallFilter(from, to, queueId, agentId, direction, npsMin, npsMax,
                waitMinSeconds, waitMaxSeconds, chosenOptionDigit, transcriptText);
        return fileResponse(exportService.exportCallsExcel(filter, businessUnitScope()), "relatorio-chamadas.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    @GetMapping("/calls/export.pdf")
    public ResponseEntity<byte[]> exportCallsPdf(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long queueId,
            @RequestParam(required = false) Long agentId,
            @RequestParam(required = false) Direction direction,
            @RequestParam(required = false) BigDecimal npsMin,
            @RequestParam(required = false) BigDecimal npsMax,
            @RequestParam(required = false) Long waitMinSeconds,
            @RequestParam(required = false) Long waitMaxSeconds,
            @RequestParam(required = false) String chosenOptionDigit,
            @RequestParam(required = false) String transcriptText) {
        CallReportFilter filter = buildCallFilter(from, to, queueId, agentId, direction, npsMin, npsMax,
                waitMinSeconds, waitMaxSeconds, chosenOptionDigit, transcriptText);
        return fileResponse(exportService.exportCallsPdf(filter, businessUnitScope()), "relatorio-chamadas.pdf", "application/pdf");
    }

    /** Exportação Excel/PDF do relatório de chat (Fase 9c.5) — mesmos filtros de {@link #chats}. */
    @GetMapping("/chats/export.xlsx")
    public ResponseEntity<byte[]> exportChatsExcel(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long queueId,
            @RequestParam(required = false) Long agentId) {
        ChatReportFilter filter = new ChatReportFilter(
                LocalDateTime.of(from, LocalTime.MIN), LocalDateTime.of(to, LocalTime.MAX), queueId, agentId);
        return fileResponse(exportService.exportChatsExcel(filter, businessUnitScope()), "relatorio-chats.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    @GetMapping("/chats/export.pdf")
    public ResponseEntity<byte[]> exportChatsPdf(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long queueId,
            @RequestParam(required = false) Long agentId) {
        ChatReportFilter filter = new ChatReportFilter(
                LocalDateTime.of(from, LocalTime.MIN), LocalDateTime.of(to, LocalTime.MAX), queueId, agentId);
        return fileResponse(exportService.exportChatsPdf(filter, businessUnitScope()), "relatorio-chats.pdf", "application/pdf");
    }

    private CallReportFilter buildCallFilter(LocalDate from, LocalDate to, Long queueId, Long agentId,
            Direction direction, BigDecimal npsMin, BigDecimal npsMax, Long waitMinSeconds, Long waitMaxSeconds,
            String chosenOptionDigit, String transcriptText) {
        return new CallReportFilter(
                LocalDateTime.of(from, LocalTime.MIN), LocalDateTime.of(to, LocalTime.MAX),
                queueId, agentId, direction, npsMin, npsMax, waitMinSeconds, waitMaxSeconds,
                chosenOptionDigit, transcriptText);
    }

    private ResponseEntity<byte[]> fileResponse(byte[] content, String filename, String contentType) {
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(org.springframework.http.MediaType.parseMediaType(contentType))
                .body(content);
    }

    /** Cada linha do relatório de chamada dispara várias consultas de enriquecimento (áudio,
     * insight, achados, execução de fluxo) — sem teto, um {@code size} grande vira abuso barato
     * de um usuário já autorizado. */
    private int clampPageSize(int size) {
        return Math.min(Math.max(size, 1), 100);
    }

    /** Reprocessa fila, agente, fluxo E chat juntos num único endpoint (decisão da fatia 9b,
     * estendida em 9c.1/9c.2) — o supervisor pede "reprocesse esse intervalo" sem precisar saber
     * que são quatro agregados internos distintos; os cálculos são independentes e baratos o
     * bastante (mesmo limite de 400 dias) pra rodar em série na mesma chamada. */
    @PostMapping("/reprocess")
    public ResponseEntity<Void> reprocess(@jakarta.validation.Valid @RequestBody ReprocessRequest request) {
        aggregationService.reprocessRange(request.from(), request.to());
        agentAggregationService.reprocessRange(request.from(), request.to());
        flowAggregationService.reprocessRange(request.from(), request.to());
        chatAggregationService.reprocessRange(request.from(), request.to());
        return ResponseEntity.ok().build();
    }

    private CallCenterReportsQueryService.Granularity parseGranularity(String raw) {
        try {
            return CallCenterReportsQueryService.Granularity.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Granularidade inválida: " + raw + " (use day/week/month/year)");
        }
    }
}
