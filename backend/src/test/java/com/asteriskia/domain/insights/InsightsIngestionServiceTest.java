package com.asteriskia.domain.insights;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * InsightsIngestionServiceTest — cobre a persistência dos eventos de
 * transferência (grupo D, Task 6) e o disparo da correlação nos dois
 * sentidos (Task 7) a cada ingestão/backfill.
 */
@ExtendWith(MockitoExtension.class)
class InsightsIngestionServiceTest {

    @Mock private CallAudioFileRepository audioFileRepository;
    @Mock private CallTranscriptSegmentRepository segmentRepository;
    @Mock private CallInsightRepository insightRepository;
    @Mock private CallInsightFindingRepository findingRepository;
    @Mock private CallTransferEventRepository transferEventRepository;
    @Mock private TransferResolutionService transferResolutionService;
    @Mock private EvaluationService evaluationService;

    private InsightsIngestionService service;

    @BeforeEach
    void setUp() {
        service = new InsightsIngestionService(audioFileRepository, segmentRepository, insightRepository,
                findingRepository, transferEventRepository, transferResolutionService, evaluationService,
                new ObjectMapper());
    }

    /** save() devolve o mesmo objeto recebido, já com o id preenchido — usado pelos
     * testes de ingest() (chamada nova, sem id ainda). */
    private void stubSaveAssignsId(Long id) {
        when(audioFileRepository.findByCallRef(any())).thenReturn(Optional.empty());
        when(audioFileRepository.save(any())).thenAnswer(invocation -> {
            CallAudioFile audioFile = invocation.getArgument(0);
            audioFile.setId(id);
            return audioFile;
        });
    }

    private IngestInsightsRequest minimalRequest(List<IngestInsightsRequest.TransferEventPayload> transferEvents) {
        return new IngestInsightsRequest(
                "VER-1", "/opt/audio/VER-1.wav", "/opt/audio/VER-1.xml",
                60, OffsetDateTime.now(), "Marina Souza", "AG-1", "39773", "4021", "16991379262", "994850",
                "inbound", "Suporte N1", null,
                "+55 11 98421-7734", "Agentes-CM01", "atendente", 1, 30, 1, 0, 10,
                "G729A", 0, 0, "SW-ORIGIN", "TRK-1", "IP", "CM01",
                transferEvents,
                0, 0, "stt-model", 0, 0, "llm-model",
                List.of(),
                new IngestInsightsRequest.InsightsPayload(null, null, null, null, "baixa", null),
                List.of(),
                null
        );
    }

    @Test
    @DisplayName("ingest: persiste os eventos de transferência do payload e dispara a resolução")
    void ingest_persistsTransferEventsAndTriggersResolution() {
        stubSaveAssignsId(100L);
        var event = new IngestInsightsRequest.TransferEventPayload(OffsetDateTime.now(), "atendente", "SW-DEST");
        service.ingest(minimalRequest(List.of(event)));

        verify(transferEventRepository).deleteByAudioFileId(100L);
        ArgumentCaptor<List<CallTransferEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(transferEventRepository).saveAll(captor.capture());
        List<CallTransferEvent> saved = captor.getValue();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getAudioFileId()).isEqualTo(100L);
        assertThat(saved.get(0).getTransferOrder()).isEqualTo((short) 1);
        assertThat(saved.get(0).getTargetSwitchCallId()).isEqualTo("SW-DEST");
        verify(transferResolutionService).resolveForAudioFile(any());
    }

    @Test
    @DisplayName("ingest: sem eventos de transferência, apenas limpa os antigos (reprocessamento)")
    void ingest_noTransferEvents_onlyDeletesOld() {
        stubSaveAssignsId(100L);
        service.ingest(minimalRequest(null));

        verify(transferEventRepository).deleteByAudioFileId(100L);
        verify(transferEventRepository, never()).saveAll(any());
        verify(transferResolutionService).resolveForAudioFile(any());
    }

    @Test
    @DisplayName("updateMetadata (backfill): atualiza só os campos novos, sem tocar status/segmentos")
    void updateMetadata_updatesOnlyMetadataFields() {
        CallAudioFile existing = CallAudioFile.builder().id(5L).callRef("VER-5").status("done").build();
        when(audioFileRepository.findByCallRef("VER-5")).thenReturn(Optional.of(existing));
        when(audioFileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var request = new InsightsMetadataUpdateRequest(
                "39773", "+55 11 98421-7734", "Agentes-CM01", "atendente", 1, 30, 0, 0, 10,
                "G729A", 0, 0, "SW-5", "TRK-1", "IP", "CM01", List.of());

        service.updateMetadata("VER-5", request);

        assertThat(existing.getAgentLoginId()).isEqualTo("39773");
        assertThat(existing.getCustomerNumber()).isEqualTo("+55 11 98421-7734");
        assertThat(existing.getStatus()).isEqualTo("done"); // não mexe no status
        verify(transferEventRepository).deleteByAudioFileId(5L);
        verify(transferResolutionService).resolveForAudioFile(existing);
    }

    @Test
    @DisplayName("updateMetadata: chamada inexistente lança IllegalArgumentException")
    void updateMetadata_unknownCallRef_throws() {
        when(audioFileRepository.findByCallRef("NAO-EXISTE")).thenReturn(Optional.empty());
        var request = new InsightsMetadataUpdateRequest(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                List.of());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.updateMetadata("NAO-EXISTE", request));
    }
}
