package com.asteriskia.domain.settings;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

/**
 * SettingsController — API REST para gestão das configurações do sistema (.env).
 *
 * GET  /api/v1/settings        → lê configurações (senhas mascaradas)
 * POST /api/v1/settings        → salva configurações no .env
 * POST /api/v1/settings/apply  → executa docker compose up -d (reinicia serviços)
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
    // POST — salva configurações no .env
    // -------------------------------------------------------------------------

    @PostMapping
    @Operation(summary = "Salva configurações no arquivo .env (não reinicia serviços)")
    public ResponseEntity<?> saveSettings(@RequestBody Map<String, String> updates) {
        if (updates == null || updates.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Nenhuma configuração enviada."));
        }
        try {
            settingsService.writeSettings(updates);
            log.info("Configurações salvas por requisição da API ({} chaves)", updates.size());
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
    // POST /apply — executa docker compose up -d
    // -------------------------------------------------------------------------

    @PostMapping("/apply")
    @Operation(summary = "Aplica as configurações reiniciando os containers Docker")
    public ResponseEntity<?> applySettings() {
        try {
            log.info("Iniciando apply de configurações via API");
            String output = settingsService.applySettings();
            boolean success = !output.contains("Exit code:") || output.contains("✅");
            return ResponseEntity.ok(new ApplyResponse(
                    success ? "success" : "error",
                    output
            ));
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Erro ao aplicar configurações: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(new ApplyResponse("error",
                            "Erro ao executar docker compose: " + e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // DTOs
    // -------------------------------------------------------------------------

    public record SuccessResponse(String message) {}
    public record ErrorResponse(String message) {}
    public record ApplyResponse(String status, String log) {}
}
