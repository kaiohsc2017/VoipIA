package com.asteriskia.domain.callcenter.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.CcQueueRepository;
import com.asteriskia.domain.callcenter.interaction.AgentState;
import com.asteriskia.domain.callcenter.interaction.AgentStateView;
import com.asteriskia.domain.callcenter.interaction.CallCenterAgentStateService;
import com.asteriskia.domain.callcenter.interaction.CcDisposition;
import com.asteriskia.domain.callcenter.interaction.CcDispositionRepository;
import com.asteriskia.domain.callcenter.cobrowsing.CobrowseConsentService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/**
 * Cobre as regras de negócio da sub-fase 7a do canal de chat (base interna): gate de
 * disponibilidade e de posse na hora de assumir/responder/encerrar uma conversa. Não testa o
 * simulador de cliente ({@code CallCenterChatTestController}) — é dev/QA only, sem regra de
 * negócio própria além de delegar para os mesmos métodos aqui cobertos.
 */
@ExtendWith(MockitoExtension.class)
class CcChatServiceTest {

    @Mock
    private CcChatChannelRepository channelRepository;
    @Mock
    private CcChatSessionRepository sessionRepository;
    @Mock
    private CcChatMessageRepository messageRepository;
    @Mock
    private CcQueueRepository queueRepository;
    @Mock
    private CcDispositionRepository dispositionRepository;
    @Mock
    private CallCenterAgentStateService agentStateService;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private ChatTranscriptExportService transcriptExportService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private CobrowseConsentService cobrowseConsentService;

    // Real, não mock — é lógica pura sobre os getters de CcAgent/CcQueue (ambos POJOs simples
    // nos testes desta classe), mais simples que stubar resolveLimit em cada teste de claim.
    private final ChatBlendingService blendingService = new ChatBlendingService();

    private CcChatService service;

    @BeforeEach
    void setUp() {
        service = new CcChatService(channelRepository, sessionRepository, messageRepository,
                queueRepository, dispositionRepository, agentStateService, messagingTemplate,
                transcriptExportService, eventPublisher, cobrowseConsentService, blendingService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private CcAgent agentOf(Long id) {
        CcAgent agent = new CcAgent();
        agent.setId(id);
        agent.setName("Agente " + id);
        return agent;
    }

    private CcQueue queueOf(Long id) {
        CcQueue queue = new CcQueue();
        queue.setId(id);
        return queue;
    }

    private CcChatSession sessionOf(Long id, String status, CcAgent assignedAgent) {
        return CcChatSession.builder()
                .id(id)
                .queue(queueOf(10L))
                .status(status)
                .assignedAgent(assignedAgent)
                .build();
    }

    @Test
    @DisplayName("claim rejeita se o agente não está DISPONIVEL")
    void claim_agentNotAvailable_throwsConflict() {
        CcAgent agent = agentOf(1L);
        when(agentStateService.currentAgent()).thenReturn(agent);
        when(agentStateService.currentState(agent)).thenReturn(new AgentStateView(1L, AgentState.PAUSA, null, null));

        assertThatThrownBy(() -> service.claim(5L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Disponível");

        verify(sessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("claim rejeita se a sessão não está mais waiting (já foi pega por outro agente)")
    void claim_sessionNotWaiting_throwsConflict() {
        CcAgent agent = agentOf(1L);
        when(agentStateService.currentAgent()).thenReturn(agent);
        when(agentStateService.currentState(agent)).thenReturn(new AgentStateView(1L, AgentState.DISPONIVEL, null, null));
        when(sessionRepository.findById(5L)).thenReturn(Optional.of(sessionOf(5L, "active", agentOf(2L))));

        assertThatThrownBy(() -> service.claim(5L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("já foi assumida");

        verify(sessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("claim bem-sucedido seta status/agente/claimedAt e publica nos tópicos certos")
    void claim_success_updatesSessionAndPublishes() {
        CcAgent agent = agentOf(1L);
        CcChatSession session = sessionOf(5L, "waiting", null);
        when(agentStateService.currentAgent()).thenReturn(agent);
        when(agentStateService.currentState(agent)).thenReturn(new AgentStateView(1L, AgentState.DISPONIVEL, null, null));
        when(sessionRepository.findById(5L)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CcChatSession result = service.claim(5L);

        assertThat(result.getStatus()).isEqualTo("active");
        assertThat(result.getAssignedAgent()).isEqualTo(agent);
        assertThat(result.getClaimedAt()).isNotNull();
        verify(messagingTemplate).convertAndSend(anyString(), (Object) any(CcChatService.ChatQueueEvent.class));
        verify(messagingTemplate).convertAndSend(anyString(), (Object) any(CcChatService.ChatSessionView.class));
        // Fase 17a — todo claim delega ao serviço de consentimento de co-browsing, que decide
        // internamente se cria algo (só se o agente tiver o toggle ligado).
        verify(cobrowseConsentService).ensureSessionForClaim(result, agent);
    }

    /** Fase 7c — os 4 quadrantes da regra de precedência skill×fila (D5, confirmado com o
     * usuário): agente nulo/zerado usa o limite da fila; agente com valor > 0 sempre prevalece. */
    @Test
    @DisplayName("claim rejeita quando o agente atingiu o limite da FILA (agente sem valor próprio)")
    void claim_queueLimitReached_agentWithoutOwnLimit_throwsConflict() {
        CcAgent agent = agentOf(1L);
        CcChatSession session = sessionOf(5L, "waiting", null);
        session.getQueue().setMaxConcurrentChats(2);
        when(agentStateService.currentAgent()).thenReturn(agent);
        when(agentStateService.currentState(agent)).thenReturn(new AgentStateView(1L, AgentState.DISPONIVEL, null, null));
        when(sessionRepository.findById(5L)).thenReturn(Optional.of(session));
        when(sessionRepository.countByAssignedAgentIdAndStatus(1L, "active")).thenReturn(2L);

        assertThatThrownBy(() -> service.claim(5L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Limite de chats simultâneos");

        verify(sessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("claim permite quando abaixo do limite da fila (agente sem valor próprio)")
    void claim_belowQueueLimit_agentWithoutOwnLimit_succeeds() {
        CcAgent agent = agentOf(1L);
        CcChatSession session = sessionOf(5L, "waiting", null);
        session.getQueue().setMaxConcurrentChats(2);
        when(agentStateService.currentAgent()).thenReturn(agent);
        when(agentStateService.currentState(agent)).thenReturn(new AgentStateView(1L, AgentState.DISPONIVEL, null, null));
        when(sessionRepository.findById(5L)).thenReturn(Optional.of(session));
        when(sessionRepository.countByAssignedAgentIdAndStatus(1L, "active")).thenReturn(1L);
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.claim(5L).getStatus()).isEqualTo("active");
    }

    @Test
    @DisplayName("claim rejeita pelo limite PRÓPRIO do agente mesmo com a fila sem limite (ou com limite maior)")
    void claim_agentOwnLimitReached_prevailsOverQueue_throwsConflict() {
        CcAgent agent = agentOf(1L);
        agent.setMaxConcurrentChats(1);
        CcChatSession session = sessionOf(5L, "waiting", null);
        session.getQueue().setMaxConcurrentChats(10); // limite da fila seria maior — não importa
        when(agentStateService.currentAgent()).thenReturn(agent);
        when(agentStateService.currentState(agent)).thenReturn(new AgentStateView(1L, AgentState.DISPONIVEL, null, null));
        when(sessionRepository.findById(5L)).thenReturn(Optional.of(session));
        when(sessionRepository.countByAssignedAgentIdAndStatus(1L, "active")).thenReturn(1L);

        assertThatThrownBy(() -> service.claim(5L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Limite de chats simultâneos");
    }

    @Test
    @DisplayName("claim sem limite algum (agente zerado e fila sem configuração) nunca consulta a contagem")
    void claim_noLimitConfigured_neverCountsSessions() {
        CcAgent agent = agentOf(1L);
        agent.setMaxConcurrentChats(0); // "zerado" — conta como sem valor próprio, cai na fila
        CcChatSession session = sessionOf(5L, "waiting", null); // fila sem maxConcurrentChats (nulo)
        when(agentStateService.currentAgent()).thenReturn(agent);
        when(agentStateService.currentState(agent)).thenReturn(new AgentStateView(1L, AgentState.DISPONIVEL, null, null));
        when(sessionRepository.findById(5L)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.claim(5L).getStatus()).isEqualTo("active");

        verify(sessionRepository, never()).countByAssignedAgentIdAndStatus(any(), any());
    }

    @Test
    @DisplayName("postMessage como agente rejeita se a sessão não está active")
    void postMessage_agentSessionNotActive_throwsConflict() {
        CcChatSession session = sessionOf(5L, "waiting", agentOf(1L));
        when(sessionRepository.findById(5L)).thenReturn(Optional.of(session));
        lenient().when(agentStateService.currentAgent()).thenReturn(agentOf(1L));

        assertThatThrownBy(() -> service.postMessage(5L, "agent", null, "oi"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("não está ativa");

        verify(messageRepository, never()).save(any());
    }

    @Test
    @DisplayName("postMessage como agente rejeita se quem envia não é o agente responsável")
    void postMessage_agentNotOwner_throwsForbidden() {
        CcChatSession session = sessionOf(5L, "active", agentOf(2L));
        when(sessionRepository.findById(5L)).thenReturn(Optional.of(session));
        when(agentStateService.currentAgent()).thenReturn(agentOf(1L));

        assertThatThrownBy(() -> service.postMessage(5L, "agent", null, "oi"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("não é o agente responsável");

        verify(messageRepository, never()).save(any());
    }

    @Test
    @DisplayName("close rejeita se quem fecha não é o dono nem ADMIN")
    void close_notOwnerNorAdmin_throwsForbidden() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("outro-usuario", null));
        CcChatSession session = sessionOf(5L, "active", agentOf(1L));
        when(sessionRepository.findById(5L)).thenReturn(Optional.of(session));
        when(agentStateService.currentAgent()).thenReturn(agentOf(2L));

        assertThatThrownBy(() -> service.close(5L, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("não é o agente responsável");

        verify(sessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("close bem-sucedido (ADMIN) seta status/closedAt/disposition mesmo não sendo o dono")
    void close_asAdmin_success() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        CcChatSession session = sessionOf(5L, "active", agentOf(1L));
        CcDisposition disposition = new CcDisposition();
        disposition.setId(9L);
        when(sessionRepository.findById(5L)).thenReturn(Optional.of(session));
        when(dispositionRepository.findById(9L)).thenReturn(Optional.of(disposition));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CcChatSession result = service.close(5L, 9L);

        assertThat(result.getStatus()).isEqualTo("closed");
        assertThat(result.getClosedAt()).isNotNull();
        assertThat(result.getDisposition()).isEqualTo(disposition);
    }

    @Test
    @DisplayName("close bem-sucedido pelo próprio agente dono, sem tabulação")
    void close_asOwner_withoutDisposition_success() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("joao", null));
        CcAgent agent = agentOf(1L);
        CcChatSession session = sessionOf(5L, "active", agent);
        when(sessionRepository.findById(5L)).thenReturn(Optional.of(session));
        when(agentStateService.currentAgent()).thenReturn(agent);
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CcChatSession result = service.close(5L, null);

        assertThat(result.getStatus()).isEqualTo("closed");
        assertThat(result.getDisposition()).isNull();
    }

    @Test
    @DisplayName("close bem-sucedido dispara a exportação do transcript (fora de transação, síncrono)")
    void close_success_triggersTranscriptExport() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("joao", null));
        CcAgent agent = agentOf(1L);
        CcChatSession session = sessionOf(5L, "active", agent);
        when(sessionRepository.findById(5L)).thenReturn(Optional.of(session));
        when(agentStateService.currentAgent()).thenReturn(agent);
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.close(5L, null);

        // Fora de uma transação gerenciada (caso do teste), não há afterCommit a esperar — o
        // serviço precisa disparar a exportação direto, não silenciosamente pular.
        verify(transcriptExportService).export(5L);
    }

    // --- Fase 24: canal com fluxo de bot ---

    @Test
    @DisplayName("startSession com canal sem fluxo de bot cria sessão 'waiting' e publica na fila (comportamento pré-Fase 24 preservado)")
    void startSession_channelWithoutBotFlow_createsWaitingSession() {
        CcChatChannel channel = CcChatChannel.builder().id(1L).code("webchat").active(true).build();
        when(channelRepository.findByCodeAndActiveTrue("webchat")).thenReturn(Optional.of(channel));
        when(queueRepository.findById(10L)).thenReturn(Optional.of(queueOf(10L)));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CcChatSession result = service.startSession("webchat", 10L, "cliente-1", "Cliente");

        assertThat(result.getStatus()).isEqualTo("waiting");
        verify(eventPublisher, never()).publishEvent(any(ChatBotSessionStartedEvent.class));
        verify(messagingTemplate).convertAndSend(anyString(), (Object) any(CcChatService.ChatQueueEvent.class));
    }

    @Test
    @DisplayName("startSession com canal de fluxo de bot ativo cria sessão 'bot' e publica ChatBotSessionStartedEvent, sem tocar a fila ainda")
    void startSession_channelWithActiveBotFlow_createsBotSessionAndPublishesEvent() {
        var flow = com.asteriskia.domain.callcenter.flow.CcFlow.builder().id(77L).active(true).build();
        CcChatChannel channel = CcChatChannel.builder()
                .id(1L).code("webchat").active(true).defaultQueue(queueOf(10L)).botFlow(flow).build();
        when(channelRepository.findByCodeAndActiveTrue("webchat")).thenReturn(Optional.of(channel));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CcChatSession result = service.startSession("webchat", "cliente-1", "Cliente");

        assertThat(result.getStatus()).isEqualTo("bot");
        ArgumentCaptor<ChatBotSessionStartedEvent> captor = ArgumentCaptor.forClass(ChatBotSessionStartedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().flowId()).isEqualTo(77L);
        verify(messagingTemplate, never()).convertAndSend(anyString(), (Object) any(CcChatService.ChatQueueEvent.class));
    }

    @Test
    @DisplayName("startSession(channelCode, customerRef, customerName) sem fila padrão responde 503, nunca 500")
    void startSession_channelWithoutDefaultQueue_throws503() {
        CcChatChannel channel = CcChatChannel.builder().id(1L).code("webchat").active(true).build();
        when(channelRepository.findByCodeAndActiveTrue("webchat")).thenReturn(Optional.of(channel));

        assertThatThrownBy(() -> service.startSession("webchat", "cliente-1", "Cliente"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("fila padrão");

        verify(sessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("postMessage de cliente publica ChatCustomerMessageReceivedEvent (mesmo sem bot em execução)")
    void postMessage_customer_publishesEvent() {
        CcChatSession session = sessionOf(5L, "bot", null);
        when(sessionRepository.findById(5L)).thenReturn(Optional.of(session));
        when(messageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.postMessage(5L, "customer", "Cliente", "quero saber o horário");

        ArgumentCaptor<ChatCustomerMessageReceivedEvent> captor = ArgumentCaptor.forClass(ChatCustomerMessageReceivedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().text()).isEqualTo("quero saber o horário");
    }

    @Test
    @DisplayName("postBotMessage grava senderType=bot sem exigir currentAgent() (roda numa thread sem autenticação de staff)")
    void postBotMessage_savesWithBotSenderType() {
        CcChatSession session = sessionOf(5L, "bot", null);
        when(sessionRepository.findById(5L)).thenReturn(Optional.of(session));
        when(messageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CcChatMessage message = service.postBotMessage(5L, "Olá, como posso ajudar?");

        assertThat(message.getSenderType()).isEqualTo("bot");
        verify(agentStateService, never()).currentAgent();
    }

    @Test
    @DisplayName("transferToHumanQueue resolve a fila por nome, muda status pra waiting e publica na fila")
    void transferToHumanQueue_success() {
        CcChatSession session = sessionOf(5L, "bot", null);
        CcQueue targetQueue = queueOf(20L);
        when(sessionRepository.findById(5L)).thenReturn(Optional.of(session));
        when(queueRepository.findByName("5001")).thenReturn(Optional.of(targetQueue));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.transferToHumanQueue(5L, "5001");

        assertThat(session.getStatus()).isEqualTo("waiting");
        assertThat(session.getQueue()).isEqualTo(targetQueue);
    }

    @Test
    @DisplayName("closeByBot encerra a sessão e é idempotente (nunca falha reencerrando uma já closed)")
    void closeByBot_success_andIdempotent() {
        CcChatSession session = sessionOf(5L, "bot", null);
        when(sessionRepository.findById(5L)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.closeByBot(5L);

        assertThat(session.getStatus()).isEqualTo("closed");
        verify(transcriptExportService).export(5L);

        service.closeByBot(5L); // já closed — não deve tentar exportar de novo nem quebrar
        verify(transcriptExportService, org.mockito.Mockito.times(1)).export(5L);
    }

    @Test
    @DisplayName("postBotMessage é ignorado quando a sessão não está mais em execução de bot (ex.: ADMIN encerrou enquanto o fluxo esperava resposta)")
    void postBotMessage_sessionNoLongerBot_isNoOp() {
        CcChatSession session = sessionOf(5L, "closed", null);
        when(sessionRepository.findById(5L)).thenReturn(Optional.of(session));

        CcChatMessage message = service.postBotMessage(5L, "Olá, como posso ajudar?");

        assertThat(message).isNull();
        verify(messageRepository, never()).save(any());
    }

    @Test
    @DisplayName("transferToHumanQueue é ignorado quando a sessão não está mais em execução de bot")
    void transferToHumanQueue_sessionNoLongerBot_isNoOp() {
        CcChatSession session = sessionOf(5L, "closed", null);
        when(sessionRepository.findById(5L)).thenReturn(Optional.of(session));

        service.transferToHumanQueue(5L, "5001");

        assertThat(session.getStatus()).isEqualTo("closed");
        verify(queueRepository, never()).findByName(anyString());
        verify(sessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("closeByBot é ignorado quando a sessão já saiu do controle do bot por outra via (ex.: já waiting)")
    void closeByBot_sessionNoLongerBot_isNoOp() {
        CcChatSession session = sessionOf(5L, "waiting", null);
        when(sessionRepository.findById(5L)).thenReturn(Optional.of(session));

        service.closeByBot(5L);

        assertThat(session.getStatus()).isEqualTo("waiting");
        verify(sessionRepository, never()).save(any());
        verify(transcriptExportService, never()).export(5L);
    }

    @Test
    @DisplayName("close publica ChatSessionEndedEvent para destravar uma eventual thread de bot ainda esperando resposta")
    void close_publishesSessionEndedEvent() {
        CcChatSession session = sessionOf(5L, "bot", null);
        when(sessionRepository.findById(5L)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        service.close(5L, null);

        ArgumentCaptor<ChatSessionEndedEvent> captor = ArgumentCaptor.forClass(ChatSessionEndedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().sessionId()).isEqualTo(5L);
    }
}
