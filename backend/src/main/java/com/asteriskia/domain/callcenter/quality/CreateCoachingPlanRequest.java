package com.asteriskia.domain.callcenter.quality;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateCoachingPlanRequest(
        @NotNull(message = "O ID do agente é obrigatório")
        Long agentId,
        Long scorecardItemId,
        Long evaluationId,
        @NotBlank(message = "O título do plano é obrigatório")
        String title,
        @NotBlank(message = "A descrição do plano é obrigatória")
        String description,
        String actionItems,
        BigDecimal targetScore,
        LocalDate deadline
) {}
