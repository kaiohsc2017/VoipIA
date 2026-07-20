package com.asteriskia.domain.insights;

import java.util.List;

/** InsightsDetailResponse — visão completa de uma chamada: metadados + transcrição + insights. */
public record InsightsDetailResponse(
        CallAudioFile audioFile,
        List<CallTranscriptSegment> segments,
        CallInsight insights,
        List<CallInsightFinding> findings,
        CallEvaluation evaluation,
        List<CallEvaluationItem> evaluationItems
) {}
