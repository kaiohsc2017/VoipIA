package com.asteriskia.domain.callcenter.copilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.chat.CcChatSession;
import com.asteriskia.domain.callcenter.chat.CcChatSessionRepository;
import com.asteriskia.domain.callcenter.interaction.CcInteraction;
import com.asteriskia.domain.callcenter.interaction.CcInteractionRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CallCenterContactHistoryServiceTest — cobre a unificação voz+chat (Fase 16.1), a exclusão do
 * contato atual e o cache de 45s (evita reconsultar o banco a cada chamada repetida em sequência,
 * comportamento explicitamente decidido nesta fase — hot-path de atendimento).
 */
class CallCenterContactHistoryServiceTest {

    private CcInteractionRepository interactionRepository;
    private CcChatSessionRepository chatSessionRepository;
    private CallCenterContactHistoryService service;

    @BeforeEach
    void setUp() {
        interactionRepository = mock(CcInteractionRepository.class);
        chatSessionRepository = mock(CcChatSessionRepository.class);
        service = new CallCenterContactHistoryService(interactionRepository, chatSessionRepository);
    }

    @Test
    @DisplayName("sam vazio retorna lista vazia sem consultar repositórios")
    void blankSam_returnsEmpty() {
        assertThat(service.historyFor("", 5, null, null)).isEmpty();
        assertThat(service.historyFor(null, 5, null, null)).isEmpty();
    }

    @Test
    @DisplayName("intercala voz e chat por data decrescente, excluindo o contato atual de cada canal")
    void unifiesAndOrdersByDateDesc_excludingCurrent() {
        var call1 = CcInteraction.builder().id(1L).queuedAt(LocalDateTime.now().minusDays(3)).build();
        var call2 = CcInteraction.builder().id(2L).queuedAt(LocalDateTime.now().minusHours(1)).build();
        var chat1 = CcChatSession.builder().id(50L).startedAt(LocalDateTime.now().minusDays(2)).build();
        when(interactionRepository.findByResolvedAdSamOrderByQueuedAtDesc("jsilva")).thenReturn(List.of(call2, call1));
        when(chatSessionRepository.findByResolvedAdSamOrderByStartedAtDesc("jsilva")).thenReturn(List.of(chat1));

        var result = service.historyFor("jsilva", 10, 2L, null);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).referenceId()).isEqualTo(50L);
        assertThat(result.get(1).referenceId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("respeita o limite informado")
    void respectsLimit() {
        var call1 = CcInteraction.builder().id(1L).queuedAt(LocalDateTime.now().minusDays(3)).build();
        var call2 = CcInteraction.builder().id(2L).queuedAt(LocalDateTime.now().minusDays(1)).build();
        when(interactionRepository.findByResolvedAdSamOrderByQueuedAtDesc("jsilva")).thenReturn(List.of(call2, call1));
        when(chatSessionRepository.findByResolvedAdSamOrderByStartedAtDesc("jsilva")).thenReturn(List.of());

        var result = service.historyFor("jsilva", 1, null, null);

        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("cache de 45s evita reconsultar o banco em chamadas repetidas do mesmo sam")
    void cachesWithinTtl() {
        when(interactionRepository.findByResolvedAdSamOrderByQueuedAtDesc("jsilva")).thenReturn(List.of());
        when(chatSessionRepository.findByResolvedAdSamOrderByStartedAtDesc("jsilva")).thenReturn(List.of());

        service.historyFor("jsilva", 5, null, null);
        service.historyFor("jsilva", 5, null, null);

        verify(interactionRepository, times(1)).findByResolvedAdSamOrderByQueuedAtDesc("jsilva");
    }
}
