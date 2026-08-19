package com.asteriskia.domain.insights.semantic;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class CallRecordingSemanticSearchDao {

    private final JdbcTemplate jdbcTemplate;

    public void updateRecordingEmbedding(Long recordId, String vectorLiteral) {
        jdbcTemplate.update(
                "UPDATE call_records SET embedding = ?::vector WHERE id = ?",
                vectorLiteral, recordId
        );
    }

    public List<SemanticSearchResultItem> searchSimilarRecordings(String queryVectorLiteral, double minSimilarity, int limit) {
        String sql = """
            SELECT cr.id, cr.call_uuid, cr.caller_number, cr.call_duration_secs, cr.transcription,
                   cr.call_date, cr.subject_tag, cr.jira_issue_key,
                   (1.0 - (cr.embedding <=> ?::vector)) AS similarity
            FROM call_records cr
            WHERE cr.embedding IS NOT NULL
              AND (1.0 - (cr.embedding <=> ?::vector)) >= ?
            ORDER BY cr.embedding <=> ?::vector ASC
            LIMIT ?
        """;

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> {
                    Timestamp ts = rs.getTimestamp("call_date");
                    LocalDateTime callDate = ts != null ? ts.toLocalDateTime() : null;
                    return new SemanticSearchResultItem(
                            rs.getLong("id"),
                            rs.getString("call_uuid"),
                            rs.getString("caller_number"),
                            rs.getInt("call_duration_secs"),
                            rs.getString("transcription"),
                            callDate,
                            rs.getString("subject_tag"),
                            rs.getString("jira_issue_key"),
                            rs.getDouble("similarity")
                    );
                },
                queryVectorLiteral, queryVectorLiteral, minSimilarity, queryVectorLiteral, limit
        );
    }

    public record SemanticSearchResultItem(
            Long id,
            String callUuid,
            String callerNumber,
            Integer durationSeconds,
            String transcription,
            LocalDateTime callDate,
            String subjectTag,
            String jiraIssueKey,
            Double similarity
    ) {}
}
