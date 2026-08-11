package com.asteriskia.domain.callcenter.recording;

import java.time.LocalDateTime;

/** RetentionConfigView — leitura da configuração de retenção das gravações do Call Center. */
public record RetentionConfigView(
        int retentionDays, LocalDateTime lastPurgeAt, Integer lastPurgeDeletedCount) {}
