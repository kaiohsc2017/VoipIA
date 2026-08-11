package com.asteriskia.domain.callcenter.reports;

import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * CallCenterQueueAggregationScheduler — consolida diariamente o agregado do dia anterior
 * (sub-fase 9a). Mirror de {@code AiModelPricingSyncScheduler}/{@code CostAlertScheduler}: job
 * agendado + método público reaproveitado pelo endpoint de reprocessamento manual
 * ({@code CallCenterReportsController.reprocess}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallCenterQueueAggregationScheduler {

    private final CallCenterQueueAggregationService aggregationService;

    @Scheduled(cron = "${app.callcenter.reports.aggregation-cron:0 30 2 * * ?}")
    public void scheduledAggregation() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        log.info("Consolidando agregado diário de filas de voz para {}", yesterday);
        aggregationService.aggregateDate(yesterday);
    }
}
