package com.asteriskia.domain.callcenter.cobrowsing;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * CallCenterCobrowseRetentionScheduler — dispara o expurgo diário de sessões de co-browsing além
 * do prazo de retenção. Mirror de {@code CallCenterRecordingRetentionScheduler} (voz): job
 * agendado + método público {@code run()}, reaproveitado pelo endpoint de disparo manual.
 */
@Component
@RequiredArgsConstructor
public class CallCenterCobrowseRetentionScheduler {

    private final CallCenterCobrowseRetentionService service;

    @Scheduled(cron = "${app.callcenter.cobrowse-retention-purge-cron:0 45 3 * * ?}")
    public void scheduledPurge() {
        run();
    }

    public CobrowseRetentionRunResult run() {
        return service.purgeExpired();
    }
}
