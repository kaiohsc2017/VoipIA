package com.asteriskia.domain.callcenter.recording;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** RetentionConfigRequest — payload de atualização do prazo de retenção (em dias). */
public record RetentionConfigRequest(@Min(1) @Max(36500) int retentionDays) {}
