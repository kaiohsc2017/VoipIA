package com.asteriskia.domain.callcenter.supervision;

/** QueueSupervisionView — estatísticas do dia de uma fila para o painel de supervisão. */
public record QueueSupervisionView(
        Long queueId,
        String queueName,
        String displayName,
        int waitingCount,
        Long longestWaitSeconds,
        int answeredToday,
        int abandonedToday,
        Double serviceLevelPercent) {}
