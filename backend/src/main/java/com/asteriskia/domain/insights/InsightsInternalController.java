package com.asteriskia.domain.insights;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * InsightsInternalController — endpoints consumidos pelo serviço asteriskia-insights.
 *
 * GET  /api/v1/internal/insights/known-refs — call_ref já persistidos, para o watcher
 *      em /opt/audio não reprocessar (e recobrar da API Gemini) pares já enviados.
 * POST /api/v1/internal/insights            — resultado completo do processamento de uma chamada.
 *
 * Protegido pelo InternalKeyFilter (X-Internal-Key) — mesmo mecanismo já usado entre
 * ai-agent/asteriskia-insights e o backend (ver UraRoutingController).
 */
@RestController
@RequestMapping("/api/v1/internal/insights")
@RequiredArgsConstructor
public class InsightsInternalController {

    private final InsightsIngestionService ingestionService;

    @GetMapping("/known-refs")
    public ResponseEntity<KnownCallRefsResponse> knownRefs() {
        return ResponseEntity.ok(new KnownCallRefsResponse(ingestionService.knownCallRefs()));
    }

    @PostMapping
    public ResponseEntity<Void> ingest(@Valid @RequestBody IngestInsightsRequest request) {
        ingestionService.ingest(request);
        return ResponseEntity.ok().build();
    }
}
