package com.asteriskia.domain.callcenter.quality;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record CoachingPlanView(
        Long id,
        Long agentId,
        String agentName,
        Long scorecardItemId,
        String scorecardItemQuestion,
        Long evaluationId,
        String title,
        String description,
        String actionItems,
        BigDecimal targetScore,
        String status,
        LocalDate deadline,
        String createdBy,
        LocalDateTime completedAt,
        LocalDateTime createdAt
) {
    public static CoachingPlanView from(CcAgentCoachingPlan plan) {
        return new CoachingPlanView(
                plan.getId(),
                plan.getAgent() != null ? plan.getAgent().getId() : null,
                plan.getAgent() != null ? plan.getAgent().getName() : null,
                plan.getScorecardItem() != null ? plan.getScorecardItem().getId() : null,
                plan.getScorecardItem() != null ? plan.getScorecardItem().getPergunta() : null,
                plan.getEvaluation() != null ? plan.getEvaluation().getId() : null,
                plan.getTitle(),
                plan.getDescription(),
                plan.getActionItems(),
                plan.getTargetScore(),
                plan.getStatus(),
                plan.getDeadline(),
                plan.getCreatedBy(),
                plan.getCompletedAt(),
                plan.getCreatedAt()
        );
    }
}
