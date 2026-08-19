package com.asteriskia.domain.insights.semantic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.asteriskia.domain.call.CallRecord;
import com.asteriskia.domain.call.CallRecordRepository;
import com.asteriskia.domain.callcenter.kb.CallCenterKbEmbeddingClient;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecordingSemanticSearchServiceTest {

    @Mock
    private CallRecordingSemanticSearchDao semanticSearchDao;

    @Mock
    private CallRecordRepository callRecordRepository;

    @Mock
    private CallCenterKbEmbeddingClient embeddingClient;

    private RecordingSemanticSearchService semanticSearchService;

    @BeforeEach
    void setUp() {
        semanticSearchService = new RecordingSemanticSearchService(semanticSearchDao, callRecordRepository, embeddingClient);
    }

    @Test
    void testSearchRecordingsWithSemanticMatch() {
        when(embeddingClient.embedAsVectorLiteral("cancelamento")).thenReturn("[0.1,0.2,0.3]");
        when(semanticSearchDao.searchSimilarRecordings(eq("[0.1,0.2,0.3]"), anyDouble(), anyInt()))
                .thenReturn(List.of(
                        new CallRecordingSemanticSearchDao.SemanticSearchResultItem(
                                1L, "ast-101", "1199998888", 120,
                                "Cliente solicitando cancelamento de contrato por instabilidade",
                                LocalDateTime.now(), "Cancelamento", "TI-100", 0.885
                        )
                ));

        var results = semanticSearchService.searchRecordings("cancelamento", 0.40, 10);

        assertNotNull(results);
        assertEquals(1, results.size());
        assertEquals(1L, results.get(0).id());
        assertEquals("Cancelamento", results.get(0).subjectTag());
        assertEquals(88.5, results.get(0).similarityPercent());
    }

    @Test
    void testIndexRecordingEmbedding() {
        CallRecord record = CallRecord.builder()
                .id(1L)
                .callUuid(UUID.randomUUID())
                .transcription("Transcrição de teste de suporte técnico")
                .build();

        when(callRecordRepository.findById(1L)).thenReturn(Optional.of(record));
        when(embeddingClient.embedAsVectorLiteral("Transcrição de teste de suporte técnico")).thenReturn("[0.5,0.6,0.7]");

        boolean success = semanticSearchService.indexRecordingEmbedding(1L);

        assertTrue(success);
        verify(semanticSearchDao).updateRecordingEmbedding(1L, "[0.5,0.6,0.7]");
    }
}
