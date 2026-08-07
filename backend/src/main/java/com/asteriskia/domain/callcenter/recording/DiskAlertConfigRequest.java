package com.asteriskia.domain.callcenter.recording;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** DiskAlertConfigRequest — payload de atualização do alerta de disco do volume de gravações. */
public record DiskAlertConfigRequest(@Min(1) @Max(100) int thresholdPercent, boolean enabled) {}
