package com.asteriskia.domain.callcenter.supervision;

import java.util.List;

/** QueueSupervisionView — estatísticas do dia de uma fila para o painel de supervisão.
 * {@code waitingCallers} (Fase 15.1) é o detalhe ao vivo de quem está esperando, obtido via AMI
 * {@code QueueStatus} — pode vir vazio mesmo com {@code waitingCount > 0} se o AMI estiver
 * indisponível no instante da consulta (fail-open, ver {@link AmiQueueStatusClient}). */
public record QueueSupervisionView(
        Long queueId,
        String queueName,
        String displayName,
        int waitingCount,
        Long longestWaitSeconds,
        int answeredToday,
        int abandonedToday,
        Double serviceLevelPercent,
        List<WaitingCallerView> waitingCallers) {}
