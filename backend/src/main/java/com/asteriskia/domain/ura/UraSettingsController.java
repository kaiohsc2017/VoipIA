package com.asteriskia.domain.ura;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * UraSettingsController — Endpoints REST para mensagens/configurações de uma URA.
 *
 * GET  /api/v1/uras/{uraId}/settings          → lista as mensagens da URA (frontend + ai-agent)
 * PUT  /api/v1/uras/{uraId}/settings/{key}    → atualiza o valor de uma mensagem
 */
@RestController
@RequestMapping("/api/v1/uras/{uraId}/settings")
@RequiredArgsConstructor
public class UraSettingsController {

    private final UraSettingsRepository repository;

    @GetMapping
        public ResponseEntity<List<UraSettings>> getAll(@PathVariable Integer uraId) {
        return ResponseEntity.ok(repository.findByUraId(uraId));
    }

    /**
     * Atualiza o valor de uma mensagem.
     * Body: { "value": "novo texto aqui" }
     */
    @PutMapping("/{key}")
        public ResponseEntity<UraSettings> update(
            @PathVariable Integer uraId,
            @PathVariable String key,
            @RequestBody Map<String, String> body) {

        return repository.findByUraIdAndKey(uraId, key)
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
