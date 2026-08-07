package com.asteriskia.domain.callcenter.supervision;

import java.time.LocalDate;

/** QueueAlertConfigView — leitura do limiar de SLA configurado para uma fila. */
public record QueueAlertConfigView(
        Long queueId,
        Integer maxWaitingCount,
        Integer minServiceLevelPercent,
        boolean enabled,
        LocalDate lastNotifiedDate) {

    public static QueueAlertConfigView from(CcQueueAlertConfig entity) {
        return new QueueAlertConfigView(
                entity.getQueueId(),
                entity.getMaxWaitingCount(),
                entity.getMinServiceLevelPercent(),
                entity.getEnabled(),
                entity.getLastNotifiedDate());
    }
}
