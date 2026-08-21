package com.asteriskia.domain.callcenter.copilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.interaction.CallCenterAgentStateService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * AgentCopilotControllerTest — prova o fechamento do IDOR encontrado na auditoria: trocar o
 * {@code agentId} na URL/corpo não vaza dado de outro agente para um usuário comum. O endpoint é
 * self-service — só {@code ROLE_ADMIN} pode consultar um agente diferente do próprio.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentCopilotControllerTest {

    @Mock private AgentCopilotService copilotService;
    @Mock private CallCenterAgentStateService agentStateService;

    private AgentCopilotController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentCopilotController(copilotService, agentStateService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("getHistory ignora o agentId do path e resolve o próprio agente autenticado")
    void getHistory_usuarioComum_ignoraAgentIdDoPath_usaProprioAgente() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("joao", null));
        CcAgent ownAgent = CcAgent.builder().id(1L).build();
        when(agentStateService.currentAgent()).thenReturn(ownAgent);
        when(copilotService.getHistoryForAgent(1L)).thenReturn(List.of());

        // Tenta ler o histórico do agente 999 (outro agente) trocando o id na URL.
        controller.getHistory(999L);

        verify(copilotService).getHistoryForAgent(1L);
        verify(copilotService, never()).getHistoryForAgent(999L);
    }

    @Test
    @DisplayName("getHistory como ROLE_ADMIN respeita o agentId explicitamente informado")
    void getHistory_admin_respeitaAgentIdInformado() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                "supervisor", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        when(copilotService.getHistoryForAgent(999L)).thenReturn(List.of());

        controller.getHistory(999L);

        verify(copilotService).getHistoryForAgent(999L);
        verify(agentStateService, never()).currentAgent();
    }

    @Test
    @DisplayName("processLiveTurn ignora o agentId do corpo e resolve o próprio agente autenticado")
    void processLiveTurn_usuarioComum_ignoraAgentIdDoCorpo() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("joao", null));
        CcAgent ownAgent = CcAgent.builder().id(1L).build();
        when(agentStateService.currentAgent()).thenReturn(ownAgent);
        when(copilotService.processLiveTurn(eq(1L), any(), any())).thenReturn(null);

        var request = new AgentCopilotController.LiveTurnRequest(999L, "chat-1", "olá");
        controller.processLiveTurn(request);

        verify(copilotService).processLiveTurn(1L, "chat-1", "olá");
        verify(copilotService, never()).processLiveTurn(eq(999L), any(), any());
    }

    @Test
    @DisplayName("getHistory sem autenticação de ROLE_ADMIN e sem authorities nenhuma continua self-service")
    void getHistory_semAuthorities_resolveProprioAgente() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("joao", null, List.of()));
        CcAgent ownAgent = CcAgent.builder().id(2L).build();
        when(agentStateService.currentAgent()).thenReturn(ownAgent);
        when(copilotService.getHistoryForAgent(2L)).thenReturn(List.of());

        var response = controller.getHistory(5L);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(copilotService).getHistoryForAgent(2L);
    }
}
