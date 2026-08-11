package com.asteriskia.domain.callcenter.reports;

import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * CallCenterAgentAggregationScheduler — consolida diariamente o agregado de agente do dia
 * anterior (sub-fase 9b). Mirror de {@code CallCenterQueueAggregationScheduler} (9a) — horário
 * padrão 10 minutos depois do agregado de fila, pra não competir pelas mesmas linhas de
 * {@code cc_interactions}/I-O de disco ao mesmo tempo.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallCenterAgentAggregationScheduler {

    private final CallCenterAgentAggregationService aggregationService;

    @Scheduled(cron = "${app.callcenter.reports.agent-aggregation-cron:0 40 2 * * ?}")
    public void scheduledAggregation() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        log.info("Consolidando agregado diário de agentes de voz para {}", yesterday);
        aggregationService.aggregateDate(yesterday);
    }
}
