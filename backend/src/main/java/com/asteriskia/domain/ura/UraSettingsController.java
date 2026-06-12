package com.asteriskia.domain.ura;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * UraSettingsController — Endpoints REST para mensagens configuráveis da URA.
 *
 * GET  /api/v1/ura/settings          → lista todas as mensagens (frontend + ai-agent)
 * PUT  /api/v1/ura/settings/{key}    → atualiza o valor de uma mensagem
 */
@RestController
@RequestMapping("/api/v1/ura/settings")
@RequiredArgsConstructor
@Tag(name = "URA Settings", description = "Mensagens configuráveis do fluxo da URA")
public class UraSettingsController {

    private final UraSettingsRepository repository;

    @GetMapping
    @Operation(summary = "Lista todas as mensagens da URA")
    public ResponseEntity<List<UraSettings>> getAll() {
        return ResponseEntity.ok(repository.findAll());
    }

    /**
     * Atualiza o valor de uma mensagem.
     * Body: { "value": "novo texto aqui" }
     */
    @PutMapping("/{key}")
    @Operation(summary = "Atualiza o texto de uma mensagem da URA")
    public ResponseEntity<UraSettings> update(
            @PathVariable String key,
            @RequestBody Map<String, String> body) {

        return repository.findById(key)
                .map(setting -> {
                    String newValue = body.getOrDefault("value", "").trim();

                    if (setting.getRequired() && newValue.isEmpty()) {
                        throw new IllegalArgumentException(
                                "A mensagem '" + setting.getLabel() + "' é obrigatória e não pode ficar vazia.");
                    }

                    setting.setValue(newValue);
                    return ResponseEntity.ok(repository.save(setting));
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
