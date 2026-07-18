package com.asteriskia.domain.insights;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * InsightsInternalController — endpoints consumidos pelo serviço asteriskia-insights.
 *
 * GET  /api/v1/internal/insights/known-refs             — call_ref já conhecidos + status atual,
 *      para o watcher em /opt/audio decidir se pula (done) ou (re)processa (pending/processing/error).
 * POST /api/v1/internal/insights/{callRef}/pending       — registra par recém-descoberto.
 * POST /api/v1/internal/insights/{callRef}/processing    — marca início real do processamento.
 * POST /api/v1/internal/insights/{callRef}/error         — marca falha, com mensagem.
 * POST /api/v1/internal/insights                         — resultado completo (sucesso).
 *
 * Protegido pelo InternalKeyFilter (X-Internal-Key) — mesmo mecanismo já usado entre
 * ai-agent/asteriskia-insights e o backend (ver UraRoutingController).
 */
@RestController
@RequestMapping("/api/v1/internal/insights")
@RequiredArgsConstructor
public class InsightsInternalController {

    private final InsightsIngestionService ingestionService;

    public record PendingRequest(@NotBlank String wavPath, @NotBlank String xmlPath) {}

    public record ProcessingRequest(String wavPath, String xmlPath) {}

    public record ErrorRequest(@NotBlank String errorMsg) {}

    @GetMapping("/known-refs")
    public ResponseEntity<KnownCallRefsResponse> knownRefs() {
        return ResponseEntity.ok(new KnownCallRefsResponse(ingestionService.knownCallRefs()));
    }

    @PostMapping("/{callRef}/pending")
    public ResponseEntity<Void> registerPending(@PathVariable String callRef, @Valid @RequestBody PendingRequest request) {
        ingestionService.registerPending(callRef, request.wavPath(), request.xmlPath());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{callRef}/processing")
    public ResponseEntity<Void> markProcessing(@PathVariable String callRef, @RequestBody(required = false) ProcessingRequest request) {
        ingestionService.markProcessing(callRef,
                request != null ? request.wavPath() : null,
                request != null ? request.xmlPath() : null);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{callRef}/error")
    public ResponseEntity<Void> markError(@PathVariable String callRef, @Valid @RequestBody ErrorRequest request) {
        ingestionService.markError(callRef, request.errorMsg());
        return ResponseEntity.ok().build();
    }

    @PostMapping
    public ResponseEntity<Void> ingest(@Valid @RequestBody IngestInsightsRequest request) {
        ingestionService.ingest(request);
        return ResponseEntity.ok().build();
    }
}
