package com.asteriskia.domain.callcenter.quality;

import java.time.LocalDateTime;

public record AppealView(
        Long id,
        Long evaluationId,
        Long agentId,
        String agentName,
        Long interactionId,
        String reason,
        String status,
        String supervisorNotes,
        String reviewedBy,
        LocalDateTime reviewedAt,
        LocalDateTime createdAt
) {
    public static AppealView from(CcEvaluationAppeal appeal) {
        return new AppealView(
                appeal.getId(),
                appeal.getEvaluation() != null ? appeal.getEvaluation().getId() : null,
                appeal.getAgent() != null ? appeal.getAgent().getId() : null,
                appeal.getAgent() != null ? appeal.getAgent().getName() : null,
                appeal.getInteraction() != null ? appeal.getInteraction().getId() : null,
                appeal.getReason(),
                appeal.getStatus(),
                appeal.getSupervisorNotes(),
                appeal.getReviewedBy(),
                appeal.getReviewedAt(),
                appeal.getCreatedAt()
        );
    }
}
