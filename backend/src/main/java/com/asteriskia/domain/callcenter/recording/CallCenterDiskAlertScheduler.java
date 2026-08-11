package com.asteriskia.domain.callcenter.recording;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * CallCenterDiskAlertScheduler — dispara a verificação diária de uso de disco do volume de
 * gravações do Call Center.
 */
@Component
@RequiredArgsConstructor
public class CallCenterDiskAlertScheduler {

    private final CallCenterDiskAlertService service;

    @Scheduled(cron = "${app.callcenter.disk-alert-cron:0 0 7 * * ?}")
    public void scheduledCheck() {
        service.checkAndNotify();
    }
}
