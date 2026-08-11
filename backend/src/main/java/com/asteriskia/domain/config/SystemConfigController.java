package com.asteriskia.domain.config;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * SystemConfigController — CRUD de configurações dinâmicas.
 *
 * <p>GET /api/v1/config → lista todas (secrets mascarados) PUT /api/v1/config/{key} → atualiza uma
 * chave POST /api/v1/config/batch → atualiza múltiplas chaves de uma vez (salvar seção)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/config")
@RequiredArgsConstructor
public class SystemConfigController {

    private static final String MASK = "••••••••";
    private final ConfigService configService;

    @GetMapping
    public ResponseEntity<List<ConfigDTO>> getAll() {
        List<ConfigDTO> dtos =
                configService.findAll().stream()
                        .map(
                                c ->
                                        new ConfigDTO(
                                                c.getKey(),
                                                c.getIsSecret() ? MASK : c.getValue(),
                                                c.getIsSecret(),
                                                c.getDescription(),
                                                c.getUpdatedAt() != null
                                                        ? c.getUpdatedAt().toString()
                                                        : null))
                        .toList();
        return ResponseEntity.ok(dtos);
    }

    @PutMapping("/{key}")
    public ResponseEntity<?> update(
            @PathVariable String key, @RequestBody Map<String, String> body, Authentication auth) {

        String value = body.getOrDefault("value", "").trim();
        String user = auth != null ? auth.getName() : "admin";

        // Não salva se o frontend enviou a máscara sem alterar
        if (MASK.equals(value)) {
            return ResponseEntity.ok(
                    Map.of("message", "Nenhuma alteração — valor mascarado ignorado."));
        }

        configService.set(key, value, user);
        return ResponseEntity.ok(
                Map.of("message", "Configuração '" + key + "' atualizada. Efeito em até 60s."));
    }

    @PostMapping("/batch")
    public ResponseEntity<?> updateBatch(
            @RequestBody Map<String, String> updates, Authentication auth) {

        if (updates == null || updates.isEmpty())
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Nenhuma configuração enviada."));

        String user = auth != null ? auth.getName() : "admin";

        // Filtra máscaras — não sobrescreve segredos que não foram alterados
        Map<String, String> filtered = new java.util.LinkedHashMap<>();
        updates.forEach(
                (k, v) -> {
                    if (!MASK.equals(v)) filtered.put(k, v);
                });

        configService.setAll(filtered, user);
        log.info("Config batch: {} chave(s) atualizadas por {}", filtered.size(), user);
        return ResponseEntity.ok(
                Map.of(
                        "message",
                        filtered.size() + " configuração(ões) atualizada(s). Efeito em até 60s.",
                        "keys",
                        filtered.keySet()));
    }
}
