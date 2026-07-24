package com.asteriskia.domain.insights;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * TransferResolutionServiceTest — cobre a correlação nos dois sentidos (Task 7
 * do plano insights-chamadas-campos-xml): a ordem de ingestão entre a chamada
 * de origem (que tem o evento de transferência) e a de destino (cujo
 * switch_call_id bate com o target_switch_call_id do evento) não é garantida.
 */
@ExtendWith(MockitoExtension.class)
class TransferResolutionServiceTest {

    @Mock
    private CallAudioFileRepository audioFileRepository;

    @Mock
    private CallTransferEventRepository transferEventRepository;

    private TransferResolutionService service;

    private TransferResolutionService newService() {
        return new TransferResolutionService(audioFileRepository, transferEventRepository);
    }

    private CallAudioFile audioFile(Long id, String switchCallId, String extension, String agentName) {
        return CallAudioFile.builder().id(id).callRef("VER-" + id)
                .switchCallId(switchCallId).extension(extension).agentName(agentName).build();
    }

    @Test
    @DisplayName("sentido origem->destino: resolve quando a chamada de destino já foi ingerida antes")
    void resolveOutgoing_destinationAlreadyIngested_resolves() {
        service = newService();
        CallAudioFile origin = audioFile(1L, "SW-ORIGIN", "4021", "Marina");
        CallTransferEvent event = CallTransferEvent.builder()
                .id(10L).audioFileId(1L).transferOrder((short) 1)
                .targetSwitchCallId("SW-DEST").resolvedAt(null).build();
        CallAudioFile destination = audioFile(2L, "SW-DEST", "4108", "Diego Ramalho");

        when(transferEventRepository.findByAudioFileIdOrderByTransferOrderAsc(1L)).thenReturn(List.of(event));
        when(audioFileRepository.findBySwitchCallId("SW-DEST")).thenReturn(Optional.of(destination));
        when(transferEventRepository.findByTargetSwitchCallIdAndResolvedAtIsNull("SW-ORIGIN")).thenReturn(List.of());

        service.resolveForAudioFile(origin);

        ArgumentCaptor<CallTransferEvent> captor = ArgumentCaptor.forClass(CallTransferEvent.class);
        verify(transferEventRepository).save(captor.capture());
        CallTransferEvent saved = captor.getValue();
        assertThat(saved.getTargetExtension()).isEqualTo("4108");
        assertThat(saved.getTargetAgentName()).isEqualTo("Diego Ramalho");
        assertThat(saved.getTargetAudioFileId()).isEqualTo(2L);
        assertThat(saved.getResolvedAt()).isNotNull();
    }

    @Test
    @DisplayName("sentido destino->origem: resolve eventos pendentes de outra chamada quando esta chamada É o destino")
    void resolveIncoming_thisCallIsTheDestination_resolvesPendingEvents() {
        service = newService();
        CallAudioFile destination = audioFile(2L, "SW-DEST", "4108", "Diego Ramalho");
        CallTransferEvent pendingEvent = CallTransferEvent.builder()
                .id(11L).audioFileId(1L).transferOrder((short) 1)
                .targetSwitchCallId("SW-DEST").resolvedAt(null).build();

        when(transferEventRepository.findByAudioFileIdOrderByTransferOrderAsc(2L)).thenReturn(List.of());
        when(transferEventRepository.findByTargetSwitchCallIdAndResolvedAtIsNull("SW-DEST")).thenReturn(List.of(pendingEvent));

        service.resolveForAudioFile(destination);

        ArgumentCaptor<CallTransferEvent> captor = ArgumentCaptor.forClass(CallTransferEvent.class);
        verify(transferEventRepository).save(captor.capture());
        CallTransferEvent saved = captor.getValue();
        assertThat(saved.getTargetExtension()).isEqualTo("4108");
        assertThat(saved.getTargetAudioFileId()).isEqualTo(2L);
        assertThat(saved.getResolvedAt()).isNotNull();
    }

    @Test
    @DisplayName("sem correlação encontrada: não resolve, não lança exceção (estado normal, não erro)")
    void noMatchFound_staysUnresolved_noException() {
        service = newService();
        CallAudioFile origin = audioFile(1L, "SW-ORIGIN", "4021", "Marina");
        CallTransferEvent event = CallTransferEvent.builder()
                .id(10L).audioFileId(1L).transferOrder((short) 1)
                .targetSwitchCallId("SW-DEST-NUNCA-INGERIDO").resolvedAt(null).build();

        when(transferEventRepository.findByAudioFileIdOrderByTransferOrderAsc(1L)).thenReturn(List.of(event));
        when(audioFileRepository.findBySwitchCallId("SW-DEST-NUNCA-INGERIDO")).thenReturn(Optional.empty());
        when(transferEventRepository.findByTargetSwitchCallIdAndResolvedAtIsNull("SW-ORIGIN")).thenReturn(List.of());

        service.resolveForAudioFile(origin);

        verify(transferEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("chamada sem switch_call_id não dispara busca de eventos pendentes (evita query com chave vazia)")
    void audioFileWithoutSwitchCallId_skipsIncomingResolution() {
        service = newService();
        CallAudioFile withoutSwitchCallId = audioFile(3L, null, "4200", "Camila");
        when(transferEventRepository.findByAudioFileIdOrderByTransferOrderAsc(3L)).thenReturn(List.of());

        service.resolveForAudioFile(withoutSwitchCallId);

        verify(transferEventRepository, never()).findByTargetSwitchCallIdAndResolvedAtIsNull(any());
    }
}
