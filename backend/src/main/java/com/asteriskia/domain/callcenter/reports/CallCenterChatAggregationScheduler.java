package com.asteriskia.domain.callcenter.reports;

import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * CallCenterChatAggregationScheduler — consolida diariamente o agregado de chat do dia anterior
 * (sub-fase 9c.2). Mesmo padrão dos demais schedulers de agregado — cron um pouco mais tarde para
 * não competir por I/O com fila/agente/fluxo na mesma madrugada.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallCenterChatAggregationScheduler {

    private final CallCenterChatAggregationService aggregationService;

    @Scheduled(cron = "${app.callcenter.reports.chat-aggregation-cron:0 50 2 * * ?}")
    public void scheduledAggregation() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        log.info("Consolidando agregado diário de chat para {}", yesterday);
        aggregationService.aggregateDate(yesterday);
    }
}
