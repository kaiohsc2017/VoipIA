package com.asteriskia.domain.callcenter.supervision;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** RedirectAgentRequest — retirar um chamador da fila e redirecioná-lo direto para um agente (Fase 15.3). */
public record RedirectAgentRequest(
        @NotBlank String sourceQueueName, @NotBlank String channelUniqueId, @NotNull Long targetAgentId) {}
