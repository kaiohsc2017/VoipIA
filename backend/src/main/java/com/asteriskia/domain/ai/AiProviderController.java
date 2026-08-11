package com.asteriskia.domain.ai;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * AiProviderController
 *
 * <p>GET /api/v1/ai/providers → lista provedores + se tem key GET
 * /api/v1/ai/providers/{id}/models?cap=STT → busca modelos via API do provedor PUT
 * /api/v1/ai/providers/{id}/key → salva API key GET /api/v1/ai/chain → lê todas as chains do banco
 * PUT /api/v1/ai/chain/{capability} → salva chain de uma capability GET /api/v1/ai/chain/active →
 * endpoint interno para o ai-agent
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiProviderController {

    private final AiProviderService service;

    // ─── Provedores ──────────────────────────────────────────────────────────

    @GetMapping("/providers")
    public ResponseEntity<?> listProviders() {
        // Retorna definição estática dos provedores + status da key
        List<AiProviderKey> keys = service.listProviderKeys();
        Map<String, Boolean> keyStatus = new java.util.HashMap<>();
        keys.forEach(k -> keyStatus.put(k.getProvider(), !k.getApiKey().isBlank()));

        List<Map<String, Object>> result =
                AiModelCatalog.PROVIDERS.stream()
                        .map(
                                p ->
                                        Map.<String, Object>of(
                                                "id", p.id(),
                                                "name", p.name(),
                                                "capabilities", p.capabilities(),
                                                "hasKey", keyStatus.getOrDefault(p.id(), false)))
                        .toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/providers/{id}/models")
    public ResponseEntity<?> listModels(
            @PathVariable String id, @RequestParam(defaultValue = "LLM") String cap) {
        try {
            List<AiModelInfo> models = service.fetchModels(id, cap);
            return ResponseEntity.ok(models);
        } catch (Exception e) {
            log.error("Erro ao buscar modelos do provedor {}: {}", id, e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Erro ao consultar API do provedor: " + e.getMessage()));
        }
    }

    @PutMapping("/providers/{id}/key")
    public ResponseEntity<?> saveKey(
            @PathVariable String id, @RequestBody Map<String, String> body, Authentication auth) {
        String apiKey = body.get("apiKey");
        if (apiKey == null || apiKey.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "apiKey é obrigatório"));

        service.saveKey(id, apiKey, auth.getName());
        return ResponseEntity.ok(Map.of("message", "Key salva com sucesso"));
    }

    // ─── Chains ──────────────────────────────────────────────────────────────

    @GetMapping("/chain")
    public ResponseEntity<?> getAllChains() {
        return ResponseEntity.ok(service.getAllChains());
    }

    @PutMapping("/chain/{capability}")
    public ResponseEntity<?> saveChain(
            @PathVariable String capability,
            @RequestBody List<AiProviderService.ChainEntryRequest> entries,
            Authentication auth) {
        if (!List.of("STT", "LLM", "TTS").contains(capability))
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "capability inválida: " + capability));

        service.saveChain(capability, entries, auth.getName());
        return ResponseEntity.ok(Map.of("message", "Chain " + capability + " salva com sucesso"));
    }

    /**
     * Endpoint interno — ai-agent busca a API key real de um provedor. Autenticado via
     * X-Internal-Key (InternalKeyFilter). Nunca exposto ao frontend — retorna a key sem
     * mascaramento.
     */
    @GetMapping("/providers/{id}/key-internal")
    public ResponseEntity<?> getKeyInternal(@PathVariable String id) {
        String key = service.getRawKey(id);
        if (key.isBlank()) {
            return ResponseEntity.ok(Map.of("apiKey", "", "configured", false));
        }
        return ResponseEntity.ok(Map.of("apiKey", key, "configured", true));
    }

    /**
     * Endpoint interno — ai-agent consulta para saber qual modelo usar. Retorna a chain ativa de
     * todas as capabilities agrupada por capability. Autenticado via X-Internal-Key
     * (InternalKeyFilter).
     */
    @GetMapping("/chain/active")
    public ResponseEntity<?> getActiveChain() {
        Map<String, List<Map<String, String>>> result = new java.util.LinkedHashMap<>();
        for (String cap : List.of("STT", "LLM", "TTS")) {
            List<Map<String, String>> entries =
                    service.getChain(cap).stream()
                            .filter(AiCapabilityChain::getIsEnabled)
                            .map(
                                    e ->
                                            Map.of(
                                                    "provider", e.getProvider(),
                                                    "modelId", e.getModelId()))
                            .toList();
            result.put(cap, entries);
        }
        return ResponseEntity.ok(result);
    }
}
