package com.asteriskia.domain.callcenter.supervision;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** CallCenterSlaAlertScheduler — dispara a verificação periódica de SLA das filas do Call Center. */
@Component
@RequiredArgsConstructor
public class CallCenterSlaAlertScheduler {

    private final CallCenterSlaAlertService service;

    @Scheduled(cron = "${app.callcenter.sla-alert-cron:0 */10 8-20 * * ?}")
    public void scheduledCheck() {
        service.checkAndNotify();
    }
}
