package com.asteriskia.domain.callcenter.recording;

import java.time.LocalDate;

/** DiskAlertConfigView — leitura da configuração + percentual de uso atual do volume de gravações. */
public record DiskAlertConfigView(
        int thresholdPercent, boolean enabled, LocalDate lastNotifiedDate, Double currentUsagePercent) {}
