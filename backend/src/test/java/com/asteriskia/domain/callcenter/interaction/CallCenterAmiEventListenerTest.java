package com.asteriskia.domain.callcenter.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.CcExtensionRepository;
import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.CcQueueRepository;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * CallCenterAmiEventListenerTest — cobre a persistência de {@code position_on_join}/
 * {@code channel_name} no join (Fase 15.1) e o fechamento de {@code QueueCallerLeave}
 * (Fase 15.1), sem depender de um Asterisk real (parsing/estado, não protocolo AMI).
 */
@ExtendWith(MockitoExtension.class)
class CallCenterAmiEventListenerTest {

    @Mock private CcQueueRepository queueRepository;
    @Mock private CcExtensionRepository extensionRepository;
    @Mock private CcInteractionRepository interactionRepository;
    @Mock private CcInteractionEventRepository interactionEventRepository;
    @Mock private CallCenterAgentStateService agentStateService;

    private CallCenterAmiEventListener listener;

    @BeforeEach
    void setUp() {
        listener =
                new CallCenterAmiEventListener(
                        queueRepository, extensionRepository, interactionRepository, interactionEventRepository, agentStateService);
    }

    @Test
    void onQueueCallerJoin_persistsPositionAndChannelName() {
        var queue = new CcQueue();
        queue.setId(1L);
        queue.setName("5001");
        when(queueRepository.findByName("5001")).thenReturn(Optional.of(queue));
        when(interactionRepository.existsByChannelUniqueId("uid-1")).thenReturn(false);
        when(interactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        listener.onQueueCallerJoin(
                Map.of(
                        "Uniqueid", "uid-1",
                        "Queue", "5001",
                        "CallerIDNum", "1199999999",
                        "Position", "3",
                        "Channel", "PJSIP/tronco-0000001a"));

        var captor = ArgumentCaptor.forClass(CcInteraction.class);
        verify(interactionRepository).save(captor.capture());
        assertThat(captor.getValue().getPositionOnJoin()).isEqualTo(3);
        assertThat(captor.getValue().getChannelName()).isEqualTo("PJSIP/tronco-0000001a");
    }

    @Test
    void onQueueCallerJoin_invalidPosition_savesNullInsteadOfThrowing() {
        when(queueRepository.findByName(any())).thenReturn(Optional.empty());
        when(interactionRepository.existsByChannelUniqueId("uid-2")).thenReturn(false);
        when(interactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        listener.onQueueCallerJoin(Map.of("Uniqueid", "uid-2", "Position", "não-é-número"));

        var captor = ArgumentCaptor.forClass(CcInteraction.class);
        verify(interactionRepository).save(captor.capture());
        assertThat(captor.getValue().getPositionOnJoin()).isNull();
    }

    @Test
    void onQueueCallerLeave_closesWaitingInteraction() {
        var interaction = CcInteraction.builder().channelUniqueId("uid-3").queuedAt(LocalDateTime.now()).build();
        when(interactionRepository.findByChannelUniqueId("uid-3")).thenReturn(Optional.of(interaction));

        listener.onQueueCallerLeave(Map.of("Uniqueid", "uid-3"));

        assertThat(interaction.getEndedAt()).isNotNull();
        verify(interactionRepository).save(interaction);
        verify(interactionEventRepository).save(any());
    }

    @Test
    void onQueueCallerLeave_alreadyAnswered_doesNothing() {
        var interaction =
                CcInteraction.builder()
                        .channelUniqueId("uid-4")
                        .queuedAt(LocalDateTime.now())
                        .answeredAt(LocalDateTime.now())
                        .build();
        when(interactionRepository.findByChannelUniqueId("uid-4")).thenReturn(Optional.of(interaction));

        listener.onQueueCallerLeave(Map.of("Uniqueid", "uid-4"));

        verify(interactionRepository, never()).save(any());
    }

    @Test
    void onQueueCallerLeave_unknownChannel_doesNothing() {
        when(interactionRepository.findByChannelUniqueId("uid-5")).thenReturn(Optional.empty());

        listener.onQueueCallerLeave(Map.of("Uniqueid", "uid-5"));

        verify(interactionRepository, never()).save(any());
    }

    /** Guarda de regressão do achado real de 2026-08-15: SO_TIMEOUT=0 (bloqueio infinito) na
     * conexão AMI trava o listener para sempre quando o Asterisk reinicia, sem log de erro nenhum
     * — reconecta só se o timeout for finito (ver AmiSessionTest para o comportamento do socket em
     * si). Este teste só impede alguém de reintroduzir 0 por engano nesta constante. */
    @Test
    void amiReadTimeout_isFinite_neverInfiniteBlocking() throws Exception {
        var field = CallCenterAmiEventListener.class.getDeclaredField("AMI_READ_TIMEOUT_MS");
        field.setAccessible(true);
        int timeout = field.getInt(null);

        assertThat(timeout).isPositive();
    }
}
