package com.asteriskia.domain.callcenter.wfm;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Proteção vem só dos matchers em {@code SecurityConfig} — não há
 * {@code @EnableMethodSecurity} configurado no projeto, então {@code @PreAuthorize} aqui seria
 * código morto (mesmo achado já corrigido em {@code SsoController}).
 */
@RestController
@RequestMapping("/api/v1/callcenter/wfm")
@RequiredArgsConstructor
public class QueuePredictiveWfmController {

    private final QueuePredictiveWfmService wfmService;

    @PostMapping("/queues/{queueId}/predictive/generate")
    public ResponseEntity<List<QueuePredictiveWfmService.WfmForecastDto>> generateForecast(
            @PathVariable Long queueId,
            @RequestParam(defaultValue = "60") int horizonMinutes) {
        return ResponseEntity.ok(wfmService.generateForecastForQueue(queueId, horizonMinutes));
    }

    // GET é só leitura (achado de auditoria corrigido) — antes gerava e gravava um forecast novo
    // quando não havia nenhum recente, um efeito colateral de escrita inesperado num verbo GET.
    // Geração/gravação agora é exclusiva do POST .../predictive/generate acima; sem forecast
    // recente, devolve lista vazia (o frontend já trata isso disparando o POST manualmente).
    @GetMapping("/queues/{queueId}/predictive")
    public ResponseEntity<List<QueuePredictiveWfmService.WfmForecastDto>> getForecasts(
            @PathVariable Long queueId) {
        return ResponseEntity.ok(wfmService.getRecentForecasts(queueId));
    }

    @GetMapping("/alerts")
    public ResponseEntity<List<QueuePredictiveWfmService.WfmForecastDto>> getActiveAlerts() {
        return ResponseEntity.ok(wfmService.getActiveBreachAlerts());
    }
}
