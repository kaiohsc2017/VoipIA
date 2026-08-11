package com.asteriskia.domain.insights;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * AgentReportController — relatórios de performance por atendente (Fase 2 do Quality
 * Management, V39). Autorização de aba em SecurityConfig (PERM_READ/WRITE_insights.reports);
 * posse (supervisor só vê o que ele pediu, ADMIN vê tudo) é sempre aplicada aqui via
 * AgentReportService, filtrando por requestedBy (username — JWT não tem user-id).
 */
@RestController
@RequestMapping("/api/v1/insights/reports")
@RequiredArgsConstructor
public class AgentReportController {

    private static final String SOURCE = "verint";

    private final AgentReportService reportService;
    private final AgentReportPdfService pdfService;

    public record CreateReportRequest(@NotBlank String agentName, @NotNull LocalDate dateFrom, @NotNull LocalDate dateTo) {}

    @PostMapping
    public ResponseEntity<?> create(@org.springframework.web.bind.annotation.RequestBody CreateReportRequest request) {
        if (request.agentName() == null || request.agentName().isBlank()
                || request.dateFrom() == null || request.dateTo() == null
                || request.dateTo().isBefore(request.dateFrom())) {
            return ResponseEntity.badRequest().body(Map.of("error", "agentName, dateFrom e dateTo (dateTo >= dateFrom) são obrigatórios"));
        }
        try {
            AgentReportDto dto = reportService.requestReport(
                    request.agentName(), SOURCE, request.dateFrom(), request.dateTo(), currentUsername(), isAdmin());
            return ResponseEntity.ok(dto);
        } catch (ReportCooldownException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Cooldown ativo — só é possível gerar 1 relatório por atendente a cada 5 dias úteis",
                            "nextAllowedAt", e.getNextAllowedAt()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<Page<AgentReportDto>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(reportService.list(currentUsername(), SOURCE, isAdmin(), PageRequest.of(page, size)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AgentReportDto> getById(@PathVariable Long id) {
        return reportService.getById(id, SOURCE, currentUsername(), isAdmin())
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportPdf(@PathVariable Long id) {
        AgentReportDto dto = reportService.getById(id, SOURCE, currentUsername(), isAdmin())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!"done".equals(dto.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Relatório ainda não concluído");
        }
        byte[] pdf = pdfService.render(dto);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"relatorio-" + id + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    @GetMapping("/agent/{agentName}/next-allowed")
    public ResponseEntity<Map<String, LocalDateTime>> nextAllowed(@PathVariable String agentName) {
        if (isAdmin()) {
            return ResponseEntity.ok(Map.of());
        }
        LocalDateTime nextAllowedAt = reportService.nextAllowed(agentName, SOURCE, currentUsername());
        return ResponseEntity.ok(nextAllowedAt != null
                ? new java.util.HashMap<>(Map.of("nextAllowedAt", nextAllowedAt))
                : Map.of());
    }

    @GetMapping("/agent/{agentName}/evolution")
    public ResponseEntity<List<AgentEvolutionSnapshot>> evolution(@PathVariable String agentName) {
        return reportService.evolution(agentName, SOURCE, currentUsername(), isAdmin())
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private String currentUsername() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return auth.getName();
    }

    private boolean isAdmin() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
