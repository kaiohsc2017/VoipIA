package com.asteriskia.domain.ai;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * AiModelPricingController — preço por milhão de tokens de cada modelo, usado para estimar o
 * custo de chamadas (Módulo 1 → aba Custos IA). Reusa a mesma proteção de
 * /api/v1/ai/** (telecom.settings — sem menu próprio, é sub-área de Configurações).
 */
@RestController
@RequestMapping("/api/v1/ai/model-pricing")
@RequiredArgsConstructor
public class AiModelPricingController {

    private final AiModelPricingRepository repository;

    @GetMapping
    public ResponseEntity<List<AiModelPricing>> list() {
        return ResponseEntity.ok(repository.findAll());
    }

    @PutMapping("/{modelId}")
    public ResponseEntity<AiModelPricing> upsert(
            @PathVariable String modelId,
            @Valid @RequestBody AiModelPricingRequest request,
            Authentication auth) {
        String provider =
                request.provider() != null && !request.provider().isBlank()
                        ? request.provider()
                        : "gemini";
        AiModelPricing entity =
                repository
                        .findById(modelId)
                        .orElseGet(
                                () -> AiModelPricing.builder().modelId(modelId).provider(provider).build());
        entity.setPricePerMillionInputUsd(request.pricePerMillionInputUsd());
        entity.setPricePerMillionOutputUsd(request.pricePerMillionOutputUsd());
        entity.setUpdatedBy(auth.getName());
        return ResponseEntity.ok(repository.save(entity));
    }
}
