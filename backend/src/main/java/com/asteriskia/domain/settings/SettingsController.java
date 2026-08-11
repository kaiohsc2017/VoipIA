package com.asteriskia.domain.settings;

import com.asteriskia.domain.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * SettingsController — API REST para gestão das configurações do sistema (.env).
 *
 * <p>GET /api/v1/settings -> lê configurações (senhas mascaradas) POST /api/v1/settings -> salva
 * configurações no .env + backup + histórico POST /api/v1/settings/apply -> inicia apply ASSÍNCRONO
 * para serviços específicos GET /api/v1/settings/apply/{jobId} -> retorna estado + log acumulado do
 * job GET /api/v1/settings/history -> últimas N alterações (padrão 50)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService settingsService;
    private final AuditService auditService;

    @GetMapping
    public ResponseEntity<?> getSettings() {
        try {
            return ResponseEntity.ok(settingsService.readAsMap());
        } catch (IOException e) {
            log.error("Erro ao ler configurações: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(
                            new ErrorResponse(
                                    "Erro ao ler arquivo de configuração: " + e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<?> saveSettings(
            @RequestBody Map<String, String> updates,
            Authentication auth,
            HttpServletRequest request) {

        if (updates == null || updates.isEmpty())
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Nenhuma configuração enviada."));

        try {
            String changedBy = auth != null ? auth.getName() : "admin";
            String ip = extractIp(request);
            settingsService.writeSettings(updates, changedBy, ip);
            log.info("Configurações salvas por {} @ {} ({} chaves)", changedBy, ip, updates.size());
            auditService.log(
                    request,
                    "SETTINGS_CHANGE",
                    updates.size()
                            + " chave(s) alterada(s): "
                            + String.join(", ", updates.keySet()),
                    true);
            return ResponseEntity.ok(
                    new SuccessResponse(
                            "Configurações salvas. Clique em 'Aplicar' para reiniciar os serviços afetados."));
        } catch (IllegalArgumentException e) {
            auditService.log(
                    request, "SETTINGS_CHANGE", "Falha ao salvar: " + e.getMessage(), false);
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        } catch (IOException e) {
            log.error("Erro ao salvar configurações: {}", e.getMessage(), e);
            auditService.log(
                    request, "SETTINGS_CHANGE", "Falha ao salvar: " + e.getMessage(), false);
            return ResponseEntity.internalServerError()
                    .body(
                            new ErrorResponse(
                                    "Erro ao gravar arquivo de configuração: " + e.getMessage()));
        }
    }

    /**
     * POST /apply
     *
     * <p>Body (opcional): { "services": ["backend", "ai-agent"] } Se "services" estiver ausente ou
     * vazio, reinicia TODOS os serviços (comportamento antigo).
     */
    @PostMapping("/apply")
    public ResponseEntity<?> startApply(
            @RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        try {
            @SuppressWarnings("unchecked")
            List<String> services =
                    body != null && body.containsKey("services")
                            ? (List<String>) body.get("services")
                            : List.of();

            log.info(
                    "Iniciando apply assíncrono — serviços: {}",
                    services.isEmpty() ? "TODOS" : services);
            String jobId = settingsService.startApplyAsync(services);
            auditService.log(
                    request,
                    "SETTINGS_CHANGE",
                    "Apply iniciado (jobId="
                            + jobId
                            + ", serviços="
                            + (services.isEmpty() ? "todos" : services)
                            + ")",
                    true);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(
                            new ApplyStartResponse(
                                    jobId,
                                    "Apply iniciado. GET /apply/" + jobId + " para acompanhar."));
        } catch (Exception e) {
            log.error("Erro ao iniciar apply: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(new ErrorResponse("Erro ao iniciar apply: " + e.getMessage()));
        }
    }

    @GetMapping("/apply/{jobId}")
    public ResponseEntity<?> getApplyStatus(@PathVariable String jobId) {
        return settingsService
                .getApplyStatus(jobId)
                .map(
                        job ->
                                ResponseEntity.ok(
                                        (Object)
                                                new ApplyStatusResponse(
                                                        job.getId(),
                                                        job.getStatus().name().toLowerCase(),
                                                        job.getLog())))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/history")
    public ResponseEntity<?> getHistory(@RequestParam(defaultValue = "50") int limit) {
        try {
            List<HistoryEntryDTO> dtos =
                    settingsService.getHistory(limit).stream()
                            .map(
                                    r ->
                                            new HistoryEntryDTO(
                                                    r.getId(),
                                                    r.getChangedAt(),
                                                    r.getChangedBy(),
                                                    r.getEnvKey(),
                                                    r.getOldValue(),
                                                    r.getNewValue(),
                                                    r.getIpAddress()))
                            .toList();
            return ResponseEntity.ok(dtos);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(new ErrorResponse("Erro ao buscar histórico: " + e.getMessage()));
        }
    }

    private String extractIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        return (xff != null && !xff.isBlank()) ? xff.split(",")[0].trim() : request.getRemoteAddr();
    }
}
