package com.asteriskia.domain.callcenter.recording;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** DiskAlertConfigRequest — payload de atualização do alerta de disco do volume de gravações. */
public record DiskAlertConfigRequest(
        @Min(value = 1, message = "Limite de uso deve ser maior ou igual a 1%")
                @Max(value = 100, message = "Limite de uso deve ser menor ou igual a 100%")
                int thresholdPercent,
        boolean enabled) {}
