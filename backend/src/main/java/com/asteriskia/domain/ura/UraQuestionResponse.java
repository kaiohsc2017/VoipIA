package com.asteriskia.domain.ura;

public record UraQuestionResponse(
        Integer id,
        Integer question_order,
        String question_text,
        String jira_field_key,
        String expected_values) {
    static UraQuestionResponse from(UraQuestion q) {
        return new UraQuestionResponse(
                q.getId(),
                q.getQuestionOrder(),
                q.getQuestionText(),
                q.getJiraFieldKey(),
                q.getExpectedValues());
    }
}
