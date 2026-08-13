package com.asteriskia.domain.callcenter.supervision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.CcAgentRepository;
import com.asteriskia.domain.callcenter.CcExtension;
import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.CcQueueRepository;
import com.asteriskia.domain.callcenter.interaction.AgentState;
import com.asteriskia.domain.callcenter.interaction.CallCenterAgentStateService;
import com.asteriskia.domain.user.AppUser;
import com.asteriskia.domain.user.AppUserRepository;
import com.asteriskia.integration.ami.AmiOriginateService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/**
 * CallCenterSupervisionActionServiceTest — ações do supervisor (Fase 6): ChanSpy exige ramal
 * provisionado, falha do AMI não audita a ação, sucesso audita e usa as opções corretas por tipo
 * de ação (escuta/sussurro/interceptação), pausa/despausa forçada reusa o motor de estado da
 * Fase 4.
 */
@ExtendWith(MockitoExtension.class)
class CallCenterSupervisionActionServiceTest {

    @Mock private CcAgentRepository agentRepository;
    @Mock private CcQueueRepository queueRepository;
    @Mock private AppUserRepository appUserRepository;
    @Mock private CcSupervisionActionRepository actionRepository;
    @Mock private AmiOriginateService amiOriginateService;
    @Mock private CallCenterAgentStateService agentStateService;
    @Mock private AmiQueueStatusClient amiQueueStatusClient;

    private CallCenterSupervisionActionService newService() {
        return new CallCenterSupervisionActionService(
                agentRepository,
                queueRepository,
                appUserRepository,
                actionRepository,
                amiOriginateService,
                agentStateService,
                amiQueueStatusClient);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(String username, Integer id, Integer extension) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(username, null));
        when(appUserRepository.findByUsername(username))
                .thenReturn(Optional.of(AppUser.builder().id(id).username(username).extension(extension).build()));
    }

    @Test
    @DisplayName("listen rejeita agente sem ramal provisionado")
    void listen_agentWithoutExtension_throws() {
        var service = newService();
        var agent = CcAgent.builder().id(10L).name("Agente Sem Ramal").build();
        when(agentRepository.findById(10L)).thenReturn(Optional.of(agent));

        assertThatThrownBy(() -> service.listen(10L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sem ramal provisionado");

        verify(amiOriginateService, never()).originateChanSpy(any(), any(), any());
        verify(actionRepository, never()).save(any());
    }

    @Test
    @DisplayName("listen usa a opção ChanSpy 'b' e audita a ação sem endedAt (contínua)")
    void listen_success_usesListenOptionsAndAudits() {
        var service = newService();
        loginAs("supervisor", 1, 9001);
        var agent = agentWithExtension(10L, "4001");
        when(agentRepository.findById(10L)).thenReturn(Optional.of(agent));
        when(amiOriginateService.originateChanSpy("9001", "4001", "b")).thenReturn(true);

        service.listen(10L);

        verify(actionRepository)
                .save(
                        org.mockito.ArgumentMatchers.argThat(
                                a ->
                                        a.getActionType() == SupervisionActionType.LISTEN
                                                && a.getEndedAt() == null
                                                && a.getSupervisorUserId().equals(1)));
    }

    @Test
    @DisplayName("whisper usa a opção ChanSpy 'bw'")
    void whisper_usesWhisperOptions() {
        var service = newService();
        loginAs("supervisor", 1, 9001);
        var agent = agentWithExtension(10L, "4001");
        when(agentRepository.findById(10L)).thenReturn(Optional.of(agent));
        when(amiOriginateService.originateChanSpy("9001", "4001", "bw")).thenReturn(true);

        service.whisper(10L);

        verify(amiOriginateService).originateChanSpy("9001", "4001", "bw");
    }

    @Test
    @DisplayName("barge usa a opção ChanSpy 'bB'")
    void barge_usesBargeOptions() {
        var service = newService();
        loginAs("supervisor", 1, 9001);
        var agent = agentWithExtension(10L, "4001");
        when(agentRepository.findById(10L)).thenReturn(Optional.of(agent));
        when(amiOriginateService.originateChanSpy("9001", "4001", "bB")).thenReturn(true);

        service.barge(10L);

        verify(amiOriginateService).originateChanSpy("9001", "4001", "bB");
    }

    @Test
    @DisplayName("falha do AMI não audita a ação")
    void chanSpy_amiFailure_doesNotAudit() {
        var service = newService();
        loginAs("supervisor", 1, 9001);
        var agent = agentWithExtension(10L, "4001");
        when(agentRepository.findById(10L)).thenReturn(Optional.of(agent));
        when(amiOriginateService.originateChanSpy(any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.listen(10L)).isInstanceOf(IllegalStateException.class);

        verify(actionRepository, never()).save(any());
    }

    @Test
    @DisplayName("forcePause chama o motor de estado da Fase 4 e audita instantaneamente")
    void forcePause_delegatesToAgentStateService() {
        var service = newService();
        loginAs("supervisor", 1, 9001);
        var agent = agentWithExtension(10L, "4001");
        when(agentRepository.findById(10L)).thenReturn(Optional.of(agent));

        service.forcePause(10L, 5L);

        verify(agentStateService).setState(agent, AgentState.PAUSA, 5L);
        verify(actionRepository)
                .save(
                        org.mockito.ArgumentMatchers.argThat(
                                a -> a.getActionType() == SupervisionActionType.FORCE_PAUSE && a.getEndedAt() != null));
    }

    @Test
    @DisplayName("forceUnpause devolve o agente a DISPONIVEL")
    void forceUnpause_setsAvailable() {
        var service = newService();
        loginAs("supervisor", 1, 9001);
        var agent = agentWithExtension(10L, "4001");
        when(agentRepository.findById(10L)).thenReturn(Optional.of(agent));

        service.forceUnpause(10L);

        verify(agentStateService).setState(agent, AgentState.DISPONIVEL, null);
    }

    @Test
    @DisplayName("whisper prefere o ramal do supervisor-como-agente (Fase 15.2) em vez do ramal do usuário")
    void whisper_supervisorIsAlsoAgent_usesAgentExtension() {
        var service = newService();
        loginAs("supervisor", 1, 9001);
        var supervisorAsAgent = agentWithExtension(2L, "4099");
        when(agentRepository.findByUserId(1)).thenReturn(Optional.of(supervisorAsAgent));
        var target = agentWithExtension(10L, "4001");
        when(agentRepository.findById(10L)).thenReturn(Optional.of(target));
        when(amiOriginateService.originateChanSpy("4099", "4001", "bw")).thenReturn(true);

        service.whisper(10L);

        verify(amiOriginateService).originateChanSpy("4099", "4001", "bw");
    }

    @Test
    @DisplayName("whisper falha com mensagem clara quando supervisor não tem ramal de nenhuma fonte")
    void whisper_supervisorWithoutAnyExtension_throwsClearError() {
        var service = newService();
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("supervisor", null));
        when(appUserRepository.findByUsername("supervisor"))
                .thenReturn(Optional.of(AppUser.builder().id(1).username("supervisor").extension(null).build()));
        when(agentRepository.findByUserId(1)).thenReturn(Optional.empty());
        var target = agentWithExtension(10L, "4001");
        when(agentRepository.findById(10L)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service.whisper(10L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Supervisor sem ramal provisionado");

        verify(amiOriginateService, never()).originateChanSpy(any(), any(), any());
    }

    @Test
    @DisplayName("redirectToQueue resolve o nome do canal ao vivo e chama Redirect via AMI")
    void redirectToQueue_success_callsRedirectWithLiveChannelName() {
        var service = newService();
        loginAs("supervisor", 1, 9001);
        var targetQueue = CcQueue.builder().id(2L).name("5002").displayName("Fila 2").build();
        when(queueRepository.findById(2L)).thenReturn(Optional.of(targetQueue));
        when(amiQueueStatusClient.queueStatus("5001"))
                .thenReturn(List.of(new WaitingCallerView(1, "1199999999", 10L, "uid-1", "PJSIP/tronco-1")));
        when(amiOriginateService.redirectChannel("PJSIP/tronco-1", "ramais-internos", "5002", 1)).thenReturn(true);

        service.redirectToQueue("5001", "uid-1", 2L);

        verify(amiOriginateService).redirectChannel("PJSIP/tronco-1", "ramais-internos", "5002", 1);
        verify(actionRepository)
                .save(
                        org.mockito.ArgumentMatchers.argThat(
                                a ->
                                        a.getActionType() == SupervisionActionType.REDIRECT_QUEUE
                                                && a.getTargetQueue() == targetQueue
                                                && a.getAgent() == null));
    }

    @Test
    @DisplayName("redirectToQueue lança 404 se o chamador não estiver mais na fila (já saiu/atendido)")
    void redirectToQueue_callerNoLongerInQueue_throwsNotFound() {
        var service = newService();
        var targetQueue = CcQueue.builder().id(2L).name("5002").displayName("Fila 2").build();
        when(queueRepository.findById(2L)).thenReturn(Optional.of(targetQueue));
        when(amiQueueStatusClient.queueStatus("5001")).thenReturn(List.of());

        assertThatThrownBy(() -> service.redirectToQueue("5001", "uid-1", 2L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("não encontrado");

        verify(amiOriginateService, never()).redirectChannel(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("redirectToAgent rejeita agente de destino sem ramal provisionado")
    void redirectToAgent_targetWithoutExtension_throws() {
        var service = newService();
        var targetAgent = CcAgent.builder().id(20L).name("Agente Sem Ramal").build();
        when(agentRepository.findById(20L)).thenReturn(Optional.of(targetAgent));

        assertThatThrownBy(() -> service.redirectToAgent("5001", "uid-1", 20L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sem ramal provisionado");

        verify(amiOriginateService, never()).redirectChannel(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("redirectToAgent resolve o ramal do agente de destino e chama Redirect via AMI")
    void redirectToAgent_success_callsRedirectToAgentExtension() {
        var service = newService();
        loginAs("supervisor", 1, 9001);
        var targetAgent = agentWithExtension(20L, "4020");
        when(agentRepository.findById(20L)).thenReturn(Optional.of(targetAgent));
        when(amiQueueStatusClient.queueStatus("5001"))
                .thenReturn(List.of(new WaitingCallerView(1, "1199999999", 10L, "uid-1", "PJSIP/tronco-1")));
        when(amiOriginateService.redirectChannel("PJSIP/tronco-1", "ramais-internos", "4020", 1)).thenReturn(true);

        service.redirectToAgent("5001", "uid-1", 20L);

        verify(amiOriginateService).redirectChannel("PJSIP/tronco-1", "ramais-internos", "4020", 1);
        verify(actionRepository)
                .save(
                        org.mockito.ArgumentMatchers.argThat(
                                a -> a.getActionType() == SupervisionActionType.REDIRECT_AGENT && a.getTargetAgent() == targetAgent));
    }

    private CcAgent agentWithExtension(Long id, String extension) {
        var agent = CcAgent.builder().id(id).name("Agente Teste").build();
        agent.setExtension(CcExtension.builder().agent(agent).extension(extension).secret("x").build());
        return agent;
    }
}
