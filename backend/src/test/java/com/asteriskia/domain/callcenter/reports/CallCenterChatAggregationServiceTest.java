package com.asteriskia.domain.callcenter.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.CcQueueRepository;
import com.asteriskia.domain.callcenter.chat.CcChatChannel;
import com.asteriskia.domain.callcenter.flow.CcFlow;
import com.asteriskia.domain.callcenter.chat.CcChatMessage;
import com.asteriskia.domain.callcenter.chat.CcChatMessageRepository;
import com.asteriskia.domain.callcenter.chat.CcChatSession;
import com.asteriskia.domain.callcenter.chat.CcChatSessionRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/**
 * Cobre a regra de negócio do agregado diário de chat (sub-fase 9c.2): contenção do bot (só conta
 * quando o canal tem fluxo de bot configurado), FRT (só resposta de agente, não de bot) e ART
 * (intervalo cliente → próxima resposta).
 */
@ExtendWith(MockitoExtension.class)
class CallCenterChatAggregationServiceTest {

    @Mock
    private CcQueueRepository queueRepository;
    @Mock
    private CcChatSessionRepository sessionRepository;
    @Mock
    private CcChatMessageRepository messageRepository;
    @Mock
    private CcAggChatDailyRepository aggRepository;

    private CallCenterChatAggregationService service;

    private CcQueue queue;
    private CcChatChannel channelWithBot;
    private CcChatChannel channelWithoutBot;
    private final LocalDate date = LocalDate.of(2026, 8, 14);

    @BeforeEach
    void setUp() {
        service = new CallCenterChatAggregationService(queueRepository, sessionRepository, messageRepository, aggRepository);
        queue = CcQueue.builder().id(1L).name("5001").displayName("Suporte").build();
        channelWithBot = CcChatChannel.builder().id(1L).botFlow(CcFlow.builder().id(10L).build()).build();
        channelWithoutBot = CcChatChannel.builder().id(2L).build();
    }

    private CcChatSession session(Long id, CcChatChannel channel, LocalDateTime started, LocalDateTime claimed, LocalDateTime closed) {
        return CcChatSession.builder().id(id).queue(queue).channel(channel).customerRef("ref-" + id)
                .startedAt(started).claimedAt(claimed).closedAt(closed).build();
    }

    @Test
    @DisplayName("contenção do bot só conta sessões de canal com fluxo de bot configurado")
    void aggregateDate_botContainment_onlyCountsChannelsWithBotFlow() {
        LocalDateTime t = date.atTime(10, 0);
        // resolvida pelo bot, sem escalar
        CcChatSession contained = session(1L, channelWithBot, t, null, t.plusMinutes(5));
        // escalou pro humano
        CcChatSession escalated = session(2L, channelWithBot, t, t.plusMinutes(1), t.plusMinutes(10));
        // canal sem bot, nunca conta pra contenção
        CcChatSession noBotChannel = session(3L, channelWithoutBot, t, t.plusMinutes(1), t.plusMinutes(10));

        when(queueRepository.findByActiveTrue()).thenReturn(List.of(queue));
        when(sessionRepository.findByQueueIdAndStartedAtBetween(eq(1L), any(), any()))
                .thenReturn(List.of(contained, escalated, noBotChannel));
        when(messageRepository.findBySessionIdInAndCreatedAtBetweenOrderByCreatedAtAsc(anyList(), any(), any()))
                .thenReturn(List.of());
        when(aggRepository.findByQueueIdAndDate(1L, date)).thenReturn(Optional.empty());

        service.aggregateDate(date);

        ArgumentCaptor<CcAggChatDaily> captor = ArgumentCaptor.forClass(CcAggChatDaily.class);
        verify(aggRepository).save(captor.capture());
        CcAggChatDaily saved = captor.getValue();
        assertThat(saved.getReceived()).isEqualTo(3);
        assertThat(saved.getClaimed()).isEqualTo(2);
        assertThat(saved.getBotContained()).isEqualTo(1);
        assertThat(saved.getBotEscalated()).isEqualTo(1);
    }

    @Test
    @DisplayName("FRT usa só a primeira resposta de agente, ART usa o par cliente->próxima resposta")
    void aggregateDate_frtAndArt() {
        LocalDateTime started = date.atTime(10, 0);
        CcChatSession sessionA = session(1L, channelWithoutBot, started, started.plusSeconds(30), null);

        when(queueRepository.findByActiveTrue()).thenReturn(List.of(queue));
        when(sessionRepository.findByQueueIdAndStartedAtBetween(eq(1L), any(), any())).thenReturn(List.of(sessionA));

        CcChatMessage customerMsg = message(1L, "customer", started.plusSeconds(5));
        CcChatMessage botMsg = message(1L, "bot", started.plusSeconds(15));
        CcChatMessage agentMsg = message(1L, "agent", started.plusSeconds(30));
        when(messageRepository.findBySessionIdInAndCreatedAtBetweenOrderByCreatedAtAsc(anyList(), any(), any()))
                .thenReturn(List.of(customerMsg, botMsg, agentMsg));
        when(aggRepository.findByQueueIdAndDate(1L, date)).thenReturn(Optional.empty());

        service.aggregateDate(date);

        ArgumentCaptor<CcAggChatDaily> captor = ArgumentCaptor.forClass(CcAggChatDaily.class);
        verify(aggRepository).save(captor.capture());
        CcAggChatDaily saved = captor.getValue();
        // FRT: started -> agentMsg (30s) — ignora o bot
        assertThat(saved.getAvgFrtSeconds()).isEqualByComparingTo("30.00");
        // ART: customerMsg -> botMsg (10s), depois nenhum outro par cliente->resposta
        assertThat(saved.getAvgResponseSeconds()).isEqualByComparingTo("10.00");
    }

    private CcChatMessage message(Long sessionId, String senderType, LocalDateTime createdAt) {
        return CcChatMessage.builder().sessionId(sessionId).senderType(senderType).body("msg").createdAt(createdAt).build();
    }

    @Test
    @DisplayName("reprocessRange rejeita intervalo invertido")
    void reprocessRange_rejectsInvertedRange() {
        assertThatThrownBy(() -> service.reprocessRange(date, date.minusDays(1)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("anterior");
    }

    @Test
    @DisplayName("reprocessRange rejeita intervalo maior que 400 dias")
    void reprocessRange_rejectsTooLargeRange() {
        assertThatThrownBy(() -> service.reprocessRange(date, date.plusDays(401)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 dias");
    }
}
