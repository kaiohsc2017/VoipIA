package com.asteriskia.domain.callcenter.reports;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
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
 * CallCenterReportsController — relatório analítico de fila de voz (sub-fase 9a do plano
 * modulo-callcenter-omnicanal.plan.md). RBAC via {@code callcenter.reports}; {@code /reprocess}
 * é {@code ROLE_ADMIN} puro (ver SecurityConfig).
 */
@RestController
@RequestMapping("/api/v1/callcenter/reports")
@RequiredArgsConstructor
public class CallCenterReportsController {

    private final CallCenterReportsQueryService queryService;
    private final CallCenterQueueAggregationService aggregationService;

    public record ReprocessRequest(@NotNull LocalDate from, @NotNull LocalDate to) {}

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

    @PostMapping("/reprocess")
    public ResponseEntity<Void> reprocess(@jakarta.validation.Valid @RequestBody ReprocessRequest request) {
        aggregationService.reprocessRange(request.from(), request.to());
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
