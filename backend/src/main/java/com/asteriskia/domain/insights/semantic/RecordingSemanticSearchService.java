package com.asteriskia.domain.insights.semantic;

import com.asteriskia.domain.call.CallRecord;
import com.asteriskia.domain.call.CallRecordRepository;
import com.asteriskia.domain.callcenter.kb.CallCenterKbEmbeddingClient;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecordingSemanticSearchService {

    private final CallRecordingSemanticSearchDao semanticSearchDao;
    private final CallRecordRepository callRecordRepository;
    private final CallCenterKbEmbeddingClient embeddingClient;

    public List<SemanticSearchResponseDto> searchRecordings(String query, double minSimilarity, int limit) {
        if (query == null || query.trim().isEmpty()) {
            return List.of();
        }

        try {
            String vectorLiteral = embeddingClient.embedAsVectorLiteral(query.trim());
            var rawResults = semanticSearchDao.searchSimilarRecordings(vectorLiteral, minSimilarity, limit);
            if (rawResults != null && !rawResults.isEmpty()) {
                return rawResults.stream()
                        .map(r -> new SemanticSearchResponseDto(
                                r.id(),
                                r.callUuid(),
                                r.callerNumber(),
                                r.durationSeconds(),
                                r.transcription(),
                                extractSnippet(r.transcription(), query),
                                r.callDate(),
                                r.subjectTag(),
                                r.jiraIssueKey(),
                                Math.round(r.similarity() * 1000.0) / 10.0
                        ))
                        .toList();
            }
        } catch (Exception e) {
            log.warn("Falha ao consultar embeddings para busca semântica, aplicando fallback textual: {}", e.getMessage());
        }

        return fallbackTextSearch(query, limit);
    }

    @Transactional
    public boolean indexRecordingEmbedding(Long callRecordId) {
        CallRecord record = callRecordRepository.findById(callRecordId).orElse(null);
        if (record == null || record.getTranscription() == null || record.getTranscription().isBlank()) {
            return false;
        }

        try {
            String vectorLiteral = embeddingClient.embedAsVectorLiteral(record.getTranscription());
            semanticSearchDao.updateRecordingEmbedding(callRecordId, vectorLiteral);
            log.info("Embedding vetorial indexado com sucesso para chamada ID {}", callRecordId);
            return true;
        } catch (Exception e) {
            log.error("Erro ao indexar embedding da chamada ID {}: {}", callRecordId, e.getMessage());
            return false;
        }
    }

    private List<SemanticSearchResponseDto> fallbackTextSearch(String query, int limit) {
        return callRecordRepository.findAll().stream()
                .filter(c -> c.getTranscription() != null && c.getTranscription().toLowerCase().contains(query.toLowerCase()))
                .limit(limit)
                .map(c -> new SemanticSearchResponseDto(
                        c.getId(),
                        c.getCallUuid() != null ? c.getCallUuid().toString() : null,
                        c.getCallerNumber(),
                        c.getCallDurationSecs(),
                        c.getTranscription(),
                        extractSnippet(c.getTranscription(), query),
                        c.getCallDate(),
                        c.getSubjectTag(),
                        c.getJiraIssueKey(),
                        75.0
                ))
                .toList();
    }

    private String extractSnippet(String text, String query) {
        if (text == null || text.isBlank()) return "";
        if (text.length() <= 200) return text;
        int idx = text.toLowerCase().indexOf(query.toLowerCase());
        if (idx == -1) {
            return text.substring(0, Math.min(200, text.length())) + "...";
        }
        int start = Math.max(0, idx - 60);
        int end = Math.min(text.length(), idx + query.length() + 100);
        return (start > 0 ? "..." : "") + text.substring(start, end) + (end < text.length() ? "..." : "");
    }

    public record SemanticSearchResponseDto(
            Long id,
            String callUuid,
            String callerNumber,
            Integer durationSeconds,
            String transcription,
            String highlightSnippet,
            LocalDateTime callDate,
            String subjectTag,
            String jiraIssueKey,
            Double similarityPercent
    ) {}
}
