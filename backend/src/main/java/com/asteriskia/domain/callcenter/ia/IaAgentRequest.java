package com.asteriskia.domain.callcenter.ia;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record IaAgentRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 500) String description,
        @NotBlank @Size(max = 50_000) String systemPrompt,
        String greeting,
        @NotBlank @Size(max = 80) String model,
        BigDecimal temperature,
        Integer topK,
        BigDecimal matchThreshold,
        @Size(max = 500) String kbTags,
        Integer maxTurns,
        BigDecimal maxCostUsd,
        Long fallbackQueueId) {}
