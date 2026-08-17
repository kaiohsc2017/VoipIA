package com.asteriskia.domain.callcenter.quality;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record DesktopEvaluationDetailView(
        Long evaluationId,
        Long audioFileId,
        Long interactionId,
        LocalDateTime callDateTime,
        String ani,
        String queueName,
        BigDecimal notaTotal,
        Boolean isFailed,
        String failReason,
        String scorecardName,
        List<EvaluationItemDetail> items,
        AppealView appeal,
        String transcript
) {
    public record EvaluationItemDetail(
            Long itemId,
            String pergunta,
            BigDecimal nota,
            Integer notaMaxima,
            BigDecimal peso,
            Boolean isCritical,
            String justificativa,
            String trechoReferencia
    ) {}
}
