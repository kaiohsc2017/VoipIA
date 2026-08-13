package com.asteriskia.domain.callcenter;

import jakarta.validation.constraints.NotBlank;

/**
 * QueueRequest — payload de criação/atualização de fila (POST/PUT /callcenter/filas).
 *
 * <p>{@code recordingEnabled}/{@code consentMessagePath} (Fase 3): quando {@code recordingEnabled}
 * vem {@code null} na criação, o service assume {@code true} (grava por padrão, mesma política de
 * fail-open aplicada pelo dialplan quando o backend não responde).
 */
public record QueueRequest(
        @NotBlank String name,
        @NotBlank String displayName,
        Integer businessUnitId,
        String strategy,
        Integer timeoutSeconds,
        Boolean recordingEnabled,
        String consentMessagePath,
        /** Fase 12.5 — opcional; se informado, clona os membros (agente+prioridade) da fila de
         * origem para a fila recém-criada, validando que a origem está no escopo de BU do
         * chamador (senão seria vazamento de composição de equipe entre BUs). */
        Long copyMembersFromQueueId,
        /** Fase 21 — pesquisa de satisfação desta fila; nulo = sem pesquisa (interruptor global
         * de cc_settings sobrepõe mesmo com pesquisa configurada). */
        Long surveyId,
        Boolean npsAlertEnabled,
        Integer npsAlertThreshold) {}
