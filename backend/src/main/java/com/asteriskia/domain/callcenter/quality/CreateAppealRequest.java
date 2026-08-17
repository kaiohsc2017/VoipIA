package com.asteriskia.domain.callcenter.quality;

import jakarta.validation.constraints.NotBlank;

public record CreateAppealRequest(
        @NotBlank(message = "A justificativa da contestação é obrigatória")
        String reason
) {}
