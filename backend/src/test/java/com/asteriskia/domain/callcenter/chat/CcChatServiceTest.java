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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

    private CcChatService service;

    @BeforeEach
    void setUp() {
        service = new CcChatService(channelRepository, sessionRepository, messageRepository,
                queueRepository, dispositionRepository, agentStateService, messagingTemplate,
                transcriptExportService);
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
}
