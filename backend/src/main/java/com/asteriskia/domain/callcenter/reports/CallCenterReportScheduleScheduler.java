package com.asteriskia.domain.callcenter.reports;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * CallCenterReportScheduleScheduler — roda a cada hora cheia (sub-fase 9c.6), diferente dos
 * schedulers diários de agregado (2h/2h30/2h40/2h50) porque cada agendamento tem sua própria hora
 * configurável, não uma única madrugada fixa.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallCenterReportScheduleScheduler {

    private final CallCenterReportScheduleService scheduleService;

    @Scheduled(cron = "${app.callcenter.reports.schedule-cron:0 0 * * * ?}")
    public void scheduledRun() {
        LocalDateTime now = LocalDateTime.now();
        log.debug("Verificando agendamentos de relatório do Call Center devidos às {}h", now.getHour());
        scheduleService.runDue(now);
    }
}
