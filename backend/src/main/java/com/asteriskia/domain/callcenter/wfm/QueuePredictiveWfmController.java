package com.asteriskia.domain.callcenter.wfm;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/callcenter/wfm")
@RequiredArgsConstructor
public class QueuePredictiveWfmController {

    private final QueuePredictiveWfmService wfmService;

    @PostMapping("/queues/{queueId}/predictive/generate")
    @PreAuthorize("hasAuthority('callcenter.wfm:write') or hasRole('ADMIN')")
    public ResponseEntity<List<QueuePredictiveWfmService.WfmForecastDto>> generateForecast(
            @PathVariable Long queueId,
            @RequestParam(defaultValue = "60") int horizonMinutes) {
        return ResponseEntity.ok(wfmService.generateForecastForQueue(queueId, horizonMinutes));
    }

    @GetMapping("/queues/{queueId}/predictive")
    @PreAuthorize("hasAuthority('callcenter.wfm:read') or hasRole('ADMIN')")
    public ResponseEntity<List<QueuePredictiveWfmService.WfmForecastDto>> getForecasts(
            @PathVariable Long queueId) {
        List<QueuePredictiveWfmService.WfmForecastDto> forecasts = wfmService.getRecentForecasts(queueId);
        if (forecasts.isEmpty()) {
            forecasts = wfmService.generateForecastForQueue(queueId, 60);
        }
        return ResponseEntity.ok(forecasts);
    }

    @GetMapping("/alerts")
    @PreAuthorize("hasAuthority('callcenter.wfm:read') or hasRole('ADMIN')")
    public ResponseEntity<List<QueuePredictiveWfmService.WfmForecastDto>> getActiveAlerts() {
        return ResponseEntity.ok(wfmService.getActiveBreachAlerts());
    }
}
