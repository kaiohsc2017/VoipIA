package com.asteriskia.domain.insights;

import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * AgentReportInternalController — endpoints consumidos pelo serviço asteriskia-insights
 * para gerar a narrativa dos relatórios de performance (Fase 2 do Quality Management,
 * V39). Protegido pelo InternalKeyFilter (X-Internal-Key), mesmo mecanismo de
 * InsightsInternalController. O agregado numérico já vem pronto (calculado no Java) —
 * o Python só chama o LLM para escrever a narrativa a partir dele.
 */
@RestController
@RequestMapping("/api/v1/internal/insights/reports")
@RequiredArgsConstructor
public class AgentReportInternalController {

    private final AgentReportService reportService;

    public record PendingReportView(Long id, String agentName, java.time.LocalDate dateFrom, java.time.LocalDate dateTo,
                                     AgentReportContent content, AgentReportEvolution evolution) {}

    public record ErrorRequest(@NotBlank String errorMsg) {}

    public record NarrativeRequest(
            List<String> pontosFortes,
            List<String> pontosMelhoria,
            List<String> recomendacoes,
            String comparacaoTextual,
            Integer llmTokensIn,
            Integer llmTokensOut,
            String llmModel
    ) {}

    @GetMapping("/pending")
    public ResponseEntity<List<PendingReportView>> pending() {
        List<PendingReportView> views = reportService.findPending().stream()
                .map(r -> {
                    AgentReportDto dto = reportService.getById(r.getId(), r.getSource(), r.getRequestedBy(), true).orElseThrow();
                    return new PendingReportView(dto.id(), dto.agentName(), dto.dateFrom(), dto.dateTo(), dto.content(), dto.evolution());
                })
                .toList();
        return ResponseEntity.ok(views);
    }

    @PostMapping("/{id}/processing")
    public ResponseEntity<Void> markProcessing(@PathVariable Long id) {
        reportService.markProcessing(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/error")
    public ResponseEntity<Void> markError(@PathVariable Long id, @org.springframework.web.bind.annotation.RequestBody ErrorRequest request) {
        reportService.markError(id, request.errorMsg());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/result")
    public ResponseEntity<Void> submitResult(@PathVariable Long id, @org.springframework.web.bind.annotation.RequestBody NarrativeRequest request) {
        AgentReportContent.Narrative narrative = new AgentReportContent.Narrative(
                request.pontosFortes(), request.pontosMelhoria(), request.recomendacoes(), request.comparacaoTextual());
        reportService.submitNarrative(id, narrative, request.llmTokensIn(), request.llmTokensOut(), request.llmModel());
        return ResponseEntity.ok().build();
    }
}
