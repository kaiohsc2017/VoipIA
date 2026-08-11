package com.asteriskia.domain.callcenter.recording;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * CallCenterRecordingRetentionScheduler — dispara o expurgo diário de gravações fora do prazo de
 * retenção. Mirror de {@code CostAlertScheduler}: job agendado + método público {@code run()},
 * reaproveitado pelo endpoint de disparo manual.
 */
@Component
@RequiredArgsConstructor
public class CallCenterRecordingRetentionScheduler {

    private final CallCenterRecordingRetentionService service;

    @Scheduled(cron = "${app.callcenter.retention-purge-cron:0 30 3 * * ?}")
    public void scheduledPurge() {
        run();
    }

    public RetentionRunResult run() {
        return service.purgeExpired();
    }
}
