package com.asteriskia.domain.callcenter.supervision;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** RedirectQueueRequest — retirar um chamador da fila e redirecioná-lo para outra fila (Fase 15.3). */
public record RedirectQueueRequest(
        @NotBlank String sourceQueueName, @NotBlank String channelUniqueId, @NotNull Long targetQueueId) {}
