package com.asteriskia.domain.callcenter.quality;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

public record ReviewAppealRequest(
        @NotBlank(message = "O status da revisão é obrigatório (APROVADA ou REJEITADA)")
        String status,
        String supervisorNotes,
        BigDecimal newScore
) {}
