package com.asteriskia.domain.settings;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * SettingsController — API REST para gestão das configurações do sistema (.env).
 *
 * GET  /api/v1/settings                    -> lê configurações (senhas mascaradas)
 * POST /api/v1/settings                    -> salva configurações no .env + backup + histórico
 * POST /api/v1/settings/apply              -> inicia apply ASSÍNCRONO, retorna 202 + { jobId }
 * GET  /api/v1/settings/apply/{jobId}      -> retorna estado + log acumulado do job
 * GET  /api/v1/settings/history            -> últimas N alterações (padrão 50)
 *
 * Todos os endpoints exigem autenticação JWT (admin).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/settings")
@RequiredArgsConstructor
@Tag(name = "Settings", description = "Gestão das configurações do sistema via arquivo .env")
public class SettingsController {

    private final SettingsService settingsService;

    // -------------------------------------------------------------------------
    // GET — lê configurações atuais
    // -------------------------------------------------------------------------

    @GetMapping
    @Operation(summary = "Lê as configurações atuais do sistema (campos secretos mascarados)")
    public ResponseEntity<?> getSettings() {
        try {
            Map<String, Object> settings = settingsService.readAsMap();
            return ResponseEntity.ok(settings);
        } catch (IOException e) {
            log.error("Erro ao ler configurações: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(new ErrorResponse("Erro ao ler arquivo de configuração: " + e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // POST — salva configurações no .env (com backup + histórico)
    // -------------------------------------------------------------------------

    @PostMapping
    @Operation(summary = "Salva configurações no arquivo .env (não reinicia serviços). Cria backup e registra histórico.")
    public ResponseEntity<?> saveSettings(
            @RequestBody Map<String, String> updates,
            Authentication auth,
            HttpServletRequest request) {

        if (updates == null || updates.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Nenhuma configuração enviada."));
        }
        try {
            String changedBy  = auth != null ? auth.getName() : "admin";
            String ipAddress  = extractIp(request);

            settingsService.writeSettings(updates, changedBy, ipAddress);
            log.info("Configurações salvas por {} @ {} ({} chaves)", changedBy, ipAddress, updates.size());

            return ResponseEntity.ok(new SuccessResponse(
                    "Configurações salvas com sucesso. Clique em 'Aplicar' para reiniciar os serviços."
            ));
        } catch (IOException e) {
            log.error("Erro ao salvar configurações: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(new ErrorResponse("Erro ao gravar arquivo de configuração: " + e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // POST /apply — inicia apply assíncrono, retorna 202 + jobId
    // -------------------------------------------------------------------------

    @PostMapping("/apply")
    @Operation(summary = "Inicia o apply das configurações (assíncrono). Retorna 202 com jobId.")
    public ResponseEntity<?> startApply() {
        try {
            log.info("Iniciando apply de configurações via API (assíncrono)");
            String jobId = settingsService.startApplyAsync();
            return ResponseEntity
                    .status(HttpStatus.ACCEPTED)
                    .body(new ApplyStartResponse(jobId, "Apply iniciado. Use GET /apply/" + jobId + " para acompanhar."));
        } catch (Exception e) {
            log.error("Erro ao iniciar apply: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(new ErrorResponse("Erro ao iniciar apply: " + e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // GET /apply/{jobId} — consulta estado do job
    // -------------------------------------------------------------------------

    @GetMapping("/apply/{jobId}")
    @Operation(summary = "Consulta o estado e log de um job de apply em andamento ou concluído.")
    public ResponseEntity<?> getApplyStatus(@PathVariable String jobId) {
        return settingsService.getApplyStatus(jobId)
                .map(job -> ResponseEntity.ok((Object) new ApplyStatusResponse(
                        job.getId(),
                        job.getStatus().name().toLowerCase(),
                        job.getLog()
                )))
                .orElse(ResponseEntity.notFound().build());
    }

    // -------------------------------------------------------------------------
    // GET /history — histórico de alterações
    // -------------------------------------------------------------------------

    @GetMapping("/history")
    @Operation(summary = "Retorna o histórico das últimas alterações de configuração (padrão: 50 registros).")
    public ResponseEntity<?> getHistory(
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        try {
            List<SettingsHistory> records = settingsService.getHistory(limit);
            List<HistoryEntryDTO> dtos = records.stream()
                    .map(r -> new HistoryEntryDTO(
                            r.getId(),
                            r.getChangedAt(),
                            r.getChangedBy(),
                            r.getEnvKey(),
                            r.getOldValue(),
                            r.getNewValue(),
                            r.getIpAddress()
                    ))
                    .toList();
            return ResponseEntity.ok(dtos);
        } catch (Exception e) {
            log.error("Erro ao buscar histórico: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(new ErrorResponse("Erro ao buscar histórico: " + e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String extractIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    // -------------------------------------------------------------------------
    // DTOs
    // -------------------------------------------------------------------------

    public record SuccessResponse(String message) {}
    public record ErrorResponse(String message) {}
    public record ApplyStartResponse(String jobId, String message) {}
    public record ApplyStatusResponse(String jobId, String status, String log) {}

    public record HistoryEntryDTO(
            Long id,
            OffsetDateTime changedAt,
            String changedBy,
            String envKey,
            String oldValue,
            String newValue,
            String ipAddress
    ) {}
}
