package com.asteriskia.domain.callcenter.nps;

import java.time.LocalDateTime;
import java.util.List;

public record SurveyDto(
        Long id,
        String name,
        SurveyMode mode,
        Integer scaleMax,
        Boolean active,
        Integer businessUnitId,
        List<QuestionDto> questions,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public record QuestionDto(Long id, Integer orderIndex, String text, String audioPath) {
        static QuestionDto from(CcSurveyQuestion q) {
            return new QuestionDto(q.getId(), q.getOrderIndex(), q.getText(), q.getAudioPath());
        }
    }

    static SurveyDto from(CcSurvey s, List<CcSurveyQuestion> questions) {
        return new SurveyDto(
                s.getId(),
                s.getName(),
                s.getMode(),
                s.getScaleMax(),
                s.getActive(),
                s.getBusinessUnit() == null ? null : s.getBusinessUnit().getId(),
                questions.stream().map(QuestionDto::from).toList(),
                s.getCreatedAt(),
                s.getUpdatedAt());
    }
}
