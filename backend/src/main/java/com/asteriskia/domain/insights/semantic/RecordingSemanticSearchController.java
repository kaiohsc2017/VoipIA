package com.asteriskia.domain.insights.semantic;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/insights/recordings")
@RequiredArgsConstructor
public class RecordingSemanticSearchController {

    private final RecordingSemanticSearchService semanticSearchService;

    @GetMapping("/semantic-search")
    @PreAuthorize("hasAuthority('insights.semantic_search:read') or hasAuthority('insights:read') or hasRole('ADMIN')")
    public ResponseEntity<List<RecordingSemanticSearchService.SemanticSearchResponseDto>> searchRecordings(
            @RequestParam String query,
            @RequestParam(defaultValue = "0.35") double minSimilarity,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(semanticSearchService.searchRecordings(query, minSimilarity, limit));
    }

    @PostMapping("/{id}/index-embedding")
    @PreAuthorize("hasAuthority('insights.semantic_search:write') or hasRole('ADMIN')")
    public ResponseEntity<Void> indexRecordingEmbedding(@PathVariable Long id) {
        boolean indexed = semanticSearchService.indexRecordingEmbedding(id);
        if (indexed) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.badRequest().build();
    }
}
