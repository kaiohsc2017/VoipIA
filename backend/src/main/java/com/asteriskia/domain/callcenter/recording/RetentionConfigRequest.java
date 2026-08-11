package com.asteriskia.domain.callcenter.recording;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** RetentionConfigRequest — payload de atualização do prazo de retenção (em dias). */
public record RetentionConfigRequest(
        @Min(value = 1, message = "Prazo de retenção deve ser maior ou igual a 1 dia")
                @Max(value = 36500, message = "Prazo de retenção deve ser menor ou igual a 36500 dias")
                int retentionDays) {}
