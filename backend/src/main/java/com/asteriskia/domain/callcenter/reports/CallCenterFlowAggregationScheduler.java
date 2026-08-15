package com.asteriskia.domain.callcenter.reports;

import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * CallCenterFlowAggregationScheduler — consolida diariamente o agregado de fluxo/URA do dia
 * anterior (sub-fase 9c.1). Mesmo padrão dos schedulers de fila (9a)/agente (9b) — cron um pouco
 * mais tarde para não competir por I/O com os dois anteriores na mesma madrugada.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallCenterFlowAggregationScheduler {

    private final CallCenterFlowAggregationService aggregationService;

    @Scheduled(cron = "${app.callcenter.reports.flow-aggregation-cron:0 40 2 * * ?}")
    public void scheduledAggregation() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        log.info("Consolidando agregado diário de fluxo/URA para {}", yesterday);
        aggregationService.aggregateDate(yesterday);
    }
}
