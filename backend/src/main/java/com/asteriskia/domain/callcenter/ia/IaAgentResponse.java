package com.asteriskia.domain.callcenter.ia;

import java.math.BigDecimal;

/** DTO de leitura — evita serializar a entidade JPA direto (LazyInitializationException em
 * {@code fallbackQueue} fora de transação, mesmo cuidado já tomado em outros controllers do
 * domínio Call Center). */
public record IaAgentResponse(
        Long id,
        String name,
        String description,
        String systemPrompt,
        String greeting,
        String model,
        BigDecimal temperature,
        Integer topK,
        BigDecimal matchThreshold,
        String kbTags,
        Integer maxTurns,
        BigDecimal maxCostUsd,
        Long fallbackQueueId,
        String fallbackQueueName,
        Boolean active) {

    static IaAgentResponse from(CcIaAgent a) {
        var queue = a.getFallbackQueue();
        return new IaAgentResponse(
                a.getId(),
                a.getName(),
                a.getDescription(),
                a.getSystemPrompt(),
                a.getGreeting(),
                a.getModel(),
                a.getTemperature(),
                a.getTopK(),
                a.getMatchThreshold(),
                a.getKbTags(),
                a.getMaxTurns(),
                a.getMaxCostUsd(),
                queue != null ? queue.getId() : null,
                queue != null ? queue.getDisplayName() : null,
                a.getActive());
    }
}
