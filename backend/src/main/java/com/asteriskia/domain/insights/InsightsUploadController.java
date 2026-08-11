package com.asteriskia.domain.insights;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * InsightsUploadController — portal do supervisor: upload em lote de áudios para
 * transcrição/análise ad-hoc (Fase 3 do Quality Management, V40). Autorização de aba em
 * SecurityConfig (PERM_READ/WRITE_insights.uploads); posse (supervisor só vê os próprios
 * lotes, ADMIN vê todos) é sempre aplicada aqui via InsightsUploadService, filtrando por
 * uploadedBy (username — JWT não tem user-id).
 */
@RestController
@RequestMapping("/api/v1/insights/uploads")
@RequiredArgsConstructor
public class InsightsUploadController {

    private final InsightsUploadService uploadService;
    private final InsightsCostService costService;

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<?> upload(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(required = false) String agentName,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String notes) {
        try {
            UploadBatchDto dto = uploadService.createBatch(files, agentName, direction, notes, currentUsername());
            return ResponseEntity.ok(dto);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<Page<UploadBatchDto>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(uploadService.listBatches(currentUsername(), isAdmin(), PageRequest.of(page, size)));
    }

    @GetMapping("/{batchId}")
    public ResponseEntity<UploadBatchDto> getById(@PathVariable UUID batchId) {
        return uploadService.batchDetail(batchId, currentUsername(), isAdmin())
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** Custo de IA das chamadas processadas via upload — só as do próprio supervisor,
     * salvo ADMIN (vê todos os uploads). Mesmas sub-abas de Custos IA/Dashboard de
     * Custos do fluxo Verint, parametrizadas por source='upload'. */
    @GetMapping("/costs")
    public ResponseEntity<Page<InsightCostView>> listCosts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String agentName,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        PageRequest pageable = PageRequest.of(page, size, org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "callStarttime"));
        return ResponseEntity.ok(costService.findCosts(uploadCostFilter(agentName, dateFrom, dateTo), pageable));
    }

    @GetMapping("/costs/summary")
    public ResponseEntity<List<InsightMonthlyCostSummary>> costsSummary(
            @RequestParam(required = false) String agentName,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        return ResponseEntity.ok(costService.summarizeByMonth(uploadCostFilter(agentName, dateFrom, dateTo)));
    }

    private InsightsCostFilter uploadCostFilter(String agentName, LocalDate dateFrom, LocalDate dateTo) {
        String ownerFilter = isAdmin() ? null : currentUsername();
        return new InsightsCostFilter(
                dateFrom != null ? LocalDateTime.of(dateFrom, LocalTime.MIN) : null,
                dateTo != null ? LocalDateTime.of(dateTo, LocalTime.MAX) : null,
                agentName, "upload", ownerFilter);
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
