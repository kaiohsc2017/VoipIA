package com.asteriskia.domain.callcenter.quality;

import jakarta.validation.constraints.NotBlank;

public record UpdateCoachingStatusRequest(
        @NotBlank(message = "O status é obrigatório (EM_ANDAMENTO, CONCLUIDO ou CANCELADO)")
        String status
) {}
