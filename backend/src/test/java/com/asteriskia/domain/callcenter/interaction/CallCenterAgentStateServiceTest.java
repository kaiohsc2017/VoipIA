package com.asteriskia.domain.callcenter.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.CcAgentRepository;
import com.asteriskia.domain.callcenter.CcPauseReason;
import com.asteriskia.domain.callcenter.CcPauseReasonRepository;
import com.asteriskia.domain.user.AppUser;
import com.asteriskia.domain.user.AppUserRepository;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * CallCenterAgentStateServiceTest — transição de estado do agente (Fase 4): fecha o estado
 * aberto anterior, exige motivo de pausa só para PAUSA, rejeita motivo de pausa fora desse
 * estado, resolve o agente atual a partir do JWT.
 */
@ExtendWith(MockitoExtension.class)
class CallCenterAgentStateServiceTest {

    @Mock private CcAgentStateRepository agentStateRepository;
    @Mock private CcAgentRepository agentRepository;
    @Mock private CcPauseReasonRepository pauseReasonRepository;
    @Mock private AppUserRepository appUserRepository;
    @Mock private SimpMessagingTemplate messagingTemplate;

    private CallCenterAgentStateService newService() {
        return new CallCenterAgentStateService(
                agentStateRepository, agentRepository, pauseReasonRepository, appUserRepository, messagingTemplate);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private CcAgent someAgent() {
        return CcAgent.builder().id(1L).name("Agente Teste").build();
    }

    @Test
    @DisplayName("setState para PAUSA sem pauseReasonId é rejeitado")
    void setState_pausaWithoutReason_throws() {
        var service = newService();
        var agent = someAgent();

        assertThatThrownBy(() -> service.setState(agent, AgentState.PAUSA, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("obrigatório");

        verify(agentStateRepository, never()).save(any());
    }

    @Test
    @DisplayName("setState com pauseReasonId fora do estado PAUSA é rejeitado")
    void setState_pauseReasonOutsidePausa_throws() {
        var service = newService();
        var agent = someAgent();

        assertThatThrownBy(() -> service.setState(agent, AgentState.DISPONIVEL, 5L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("só é aceito para o estado PAUSA");

        verify(agentStateRepository, never()).save(any());
    }

    @Test
    @DisplayName("setState fecha o estado aberto anterior e broadcast via STOMP")
    void setState_closesPreviousOpenState_andBroadcasts() {
        var service = newService();
        var agent = someAgent();
        var openState =
                CcAgentState.builder().id(10L).agent(agent).state(AgentState.DISPONIVEL).build();
        when(agentStateRepository.findByAgentIdAndEndedAtIsNull(agent.getId()))
                .thenReturn(Optional.of(openState));
        when(agentStateRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.setState(agent, AgentState.OFFLINE, null);

        assertThat(result.state()).isEqualTo(AgentState.OFFLINE);
        assertThat(openState.getEndedAt()).isNotNull();
        verify(messagingTemplate).convertAndSend("/topic/callcenter/agent-states", (Object) result);
    }

    @Test
    @DisplayName("setState PAUSA com motivo inativo é rejeitado")
    void setState_pauseReasonInactive_throws() {
        var service = newService();
        var agent = someAgent();
        when(pauseReasonRepository.findByIdAndActiveTrue(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setState(agent, AgentState.PAUSA, 5L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Motivo de pausa inválido");
    }

    @Test
    @DisplayName("setState PAUSA com motivo válido grava o motivo na nova linha")
    void setState_pausaWithValidReason_savesReason() {
        var service = newService();
        var agent = someAgent();
        var reason = CcPauseReason.builder().id(5L).code("ALMOCO").label("Almoço").build();
        when(agentStateRepository.findByAgentIdAndEndedAtIsNull(agent.getId()))
                .thenReturn(Optional.empty());
        when(pauseReasonRepository.findByIdAndActiveTrue(5L)).thenReturn(Optional.of(reason));
        when(agentStateRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.setState(agent, AgentState.PAUSA, 5L);

        assertThat(result.state()).isEqualTo(AgentState.PAUSA);
        assertThat(result.pauseReasonLabel()).isEqualTo("Almoço");
    }

    @Test
    @DisplayName("currentAgent resolve o agente a partir do usuário autenticado")
    void currentAgent_resolvesFromAuthenticatedUser() {
        var service = newService();
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("joao", null));
        var appUser = AppUser.builder().id(42).username("joao").build();
        var agent = someAgent();
        when(appUserRepository.findByUsername("joao")).thenReturn(Optional.of(appUser));
        when(agentRepository.findByUserId(42)).thenReturn(Optional.of(agent));

        assertThat(service.currentAgent()).isEqualTo(agent);
    }

    @Test
    @DisplayName("currentAgent rejeita usuário sem agente vinculado")
    void currentAgent_userWithoutAgent_throws() {
        var service = newService();
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("joao", null));
        var appUser = AppUser.builder().id(42).username("joao").build();
        when(appUserRepository.findByUsername("joao")).thenReturn(Optional.of(appUser));
        when(agentRepository.findByUserId(42)).thenReturn(Optional.empty());

        assertThatThrownBy(service::currentAgent)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("não está vinculado");
    }
}
