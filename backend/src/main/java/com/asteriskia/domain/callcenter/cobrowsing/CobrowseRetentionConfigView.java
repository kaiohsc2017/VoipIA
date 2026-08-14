package com.asteriskia.domain.callcenter.cobrowsing;

import java.time.LocalDateTime;

/** CobrowseRetentionConfigView — leitura da configuração de retenção do co-browsing. */
public record CobrowseRetentionConfigView(
        int retentionDays, LocalDateTime lastPurgeAt, Integer lastPurgeDeletedCount) {}
