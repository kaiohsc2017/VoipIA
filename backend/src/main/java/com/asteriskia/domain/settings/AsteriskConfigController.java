package com.asteriskia.domain.settings;

import com.asteriskia.domain.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * AsteriskConfigController — Edição dos arquivos de configuração do Asterisk via UI.
 *
 * <p>Gerencia dois arquivos que ficam em /etc/asterisk (montado via volume): - pjsip.conf.template
 * → tronco SIP (seção [tronco-sip]) - extensions.conf → plano de discagem (rotas de entrada e
 * saída)
 *
 * <p>GET /api/v1/asterisk-config/tronco → bloco [tronco-sip] atual POST
 * /api/v1/asterisk-config/tronco → salva bloco + reload res_pjsip via AMI GET
 * /api/v1/asterisk-config/rotas → conteúdo completo do extensions.conf POST
 * /api/v1/asterisk-config/rotas → salva extensions.conf + reload dialplan via AMI
 *
 * <p>O reload via AMI é best-effort: falha de conexão retorna status descritivo mas não impede o
 * salvamento do arquivo.
 *
 * <p>A lógica de I/O de arquivo, parsing de seção pjsip e protocolo AMI mora em {@link
 * AsteriskConfigService} (fase 20, O3.2 da refatoração) — este controller cuida só de
 * request/response, autorização e auditoria.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/asterisk-config")
@RequiredArgsConstructor
public class AsteriskConfigController {

    private final AsteriskConfigService asteriskConfigService;
    private final SettingsService settingsService;
    private final AuditService auditService;

    // =========================================================================
    // TRONCO SIP — pjsip.conf.template
    // =========================================================================

    @GetMapping("/tronco")
    public ResponseEntity<?> getTronco() {
        try {
            String block = asteriskConfigService.readTroncoBlock();
            return ResponseEntity.ok(Map.of("block", block));
        } catch (IOException e) {
            log.error("Erro ao ler pjsip.conf.template: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "Erro ao ler pjsip.conf.template: " + e.getMessage()));
        }
    }

    @PostMapping("/tronco")
    public ResponseEntity<?> saveTronco(
            @RequestBody Map<String, String> body,
            Authentication auth,
            HttpServletRequest request) {

        String block = body.get("block");
        if (block == null || block.isBlank())
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Campo 'block' não pode ser vazio."));

        String normalized = block.strip();
        try {
            // 1. Substitui seção no template e extrai campos mapeados
            Map<String, String> envUpdates = asteriskConfigService.saveTronco(normalized);

            // 2. Atualiza .env com campos mapeados
            if (!envUpdates.isEmpty()) {
                settingsService.writeSettings(
                        envUpdates, auth != null ? auth.getName() : "admin", extractIp(request));
            }

            // 3. Reload PJSIP via AMI
            String reloadStatus = asteriskConfigService.reloadPjsip();

            auditService.log(
                    request,
                    "ASTERISK_TRONCO_SAVE",
                    "Bloco [tronco-sip] atualizado. AMI reload: " + reloadStatus,
                    true);

            return ResponseEntity.ok(
                    Map.of(
                            "message",
                            "Tronco SIP salvo com sucesso.",
                            "reloadStatus",
                            reloadStatus,
                            "envKeys",
                            envUpdates.keySet()));
        } catch (IOException e) {
            log.error("Erro ao salvar tronco SIP: {}", e.getMessage(), e);
            auditService.log(request, "ASTERISK_TRONCO_SAVE", "Falha: " + e.getMessage(), false);
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "Erro ao salvar: " + e.getMessage()));
        }
    }

    // =========================================================================
    // ROTAS — extensions.conf
    // =========================================================================

    @GetMapping("/rotas")
    public ResponseEntity<?> getRotas() {
        try {
            String content = asteriskConfigService.readRotas();
            return ResponseEntity.ok(Map.of("content", content));
        } catch (IOException e) {
            log.error("Erro ao ler extensions.conf: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "Erro ao ler extensions.conf: " + e.getMessage()));
        }
    }

    @PostMapping("/rotas")
    public ResponseEntity<?> saveRotas(
            @RequestBody Map<String, String> body,
            Authentication auth,
            HttpServletRequest request) {

        String content = body.get("content");
        if (content == null || content.isBlank())
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Campo 'content' não pode ser vazio."));

        try {
            asteriskConfigService.saveRotas(content.strip() + "\n");

            String reloadStatus = asteriskConfigService.reloadDialplan();

            auditService.log(
                    request,
                    "ASTERISK_ROTAS_SAVE",
                    "extensions.conf atualizado. AMI reload: " + reloadStatus,
                    true);

            return ResponseEntity.ok(
                    Map.of("message", "Rotas salvas com sucesso.", "reloadStatus", reloadStatus));
        } catch (IOException e) {
            log.error("Erro ao salvar extensions.conf: {}", e.getMessage(), e);
            auditService.log(request, "ASTERISK_ROTAS_SAVE", "Falha: " + e.getMessage(), false);
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "Erro ao salvar: " + e.getMessage()));
        }
    }

    private String extractIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        return (xff != null && !xff.isBlank()) ? xff.split(",")[0].trim() : req.getRemoteAddr();
    }
}
