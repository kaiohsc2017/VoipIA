package com.asteriskia.domain.callcenter.supervision;

/** QueueAlertConfigRequest — limiar de SLA configurado para uma fila. */
public record QueueAlertConfigRequest(Integer maxWaitingCount, Integer minServiceLevelPercent, boolean enabled) {}
