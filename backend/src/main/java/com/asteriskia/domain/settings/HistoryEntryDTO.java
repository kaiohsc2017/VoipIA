package com.asteriskia.domain.settings;

import java.time.OffsetDateTime;

public record HistoryEntryDTO(
        Long id,
        OffsetDateTime changedAt,
        String changedBy,
        String envKey,
        String oldValue,
        String newValue,
        String ipAddress) {}
