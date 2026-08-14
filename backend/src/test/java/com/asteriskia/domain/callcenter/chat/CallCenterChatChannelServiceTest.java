package com.asteriskia.domain.callcenter.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.CcQueueRepository;
import com.asteriskia.domain.callcenter.flow.CcFlow;
import com.asteriskia.domain.callcenter.flow.CcFlowRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CallCenterChatChannelServiceTest {

    @Mock
    private CcChatChannelRepository channelRepository;
    @Mock
    private CcQueueRepository queueRepository;
    @Mock
    private CcFlowRepository flowRepository;

    private CallCenterChatChannelService service;

    @BeforeEach
    void setUp() {
        service = new CallCenterChatChannelService(channelRepository, queueRepository, flowRepository);
    }

    @Test
    @DisplayName("create rejeita código já usado por outro canal ativo")
    void create_duplicateActiveCode_throws() {
        when(channelRepository.findByCodeAndActiveTrue("webchat"))
                .thenReturn(Optional.of(CcChatChannel.builder().id(1L).code("webchat").build()));

        assertThatThrownBy(() -> service.create(
                        new ChatChannelRequest("webchat", "Site", "webchat", null, null, null, null, true, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Já existe");

        verify(channelRepository, never()).save(any());
    }

    @Test
    @DisplayName("create resolve a fila padrão e o fluxo de bot informados, sem exigir nenhum dos dois")
    void create_resolvesQueueAndBotFlow() {
        var queue = new CcQueue();
        queue.setId(10L);
        var flow = CcFlow.builder().id(20L).build();
        when(channelRepository.findByCodeAndActiveTrue("webchat")).thenReturn(Optional.empty());
        when(queueRepository.findById(10L)).thenReturn(Optional.of(queue));
        when(flowRepository.findById(20L)).thenReturn(Optional.of(flow));
        when(channelRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.create(new ChatChannelRequest("webchat", "Site", "webchat", 10L, 20L, "Olá!", null, true, null, null));

        assertThat(result.getDefaultQueue()).isEqualTo(queue);
        assertThat(result.getBotFlow()).isEqualTo(flow);
        assertThat(result.getGreetingMessage()).isEqualTo("Olá!");
    }

    @Test
    @DisplayName("create sem fila nem fluxo de bot é permitido (canal pode ser completado depois)")
    void create_withoutQueueOrBotFlow_allowed() {
        when(channelRepository.findByCodeAndActiveTrue("webchat")).thenReturn(Optional.empty());
        when(channelRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.create(new ChatChannelRequest("webchat", "Site", null, null, null, null, null, null, null, null));

        assertThat(result.getDefaultQueue()).isNull();
        assertThat(result.getBotFlow()).isNull();
        assertThat(result.getType()).isEqualTo("webchat");
        assertThat(result.getActive()).isTrue();
    }
}
