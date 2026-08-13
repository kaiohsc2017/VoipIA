package com.asteriskia.domain.callcenter.supervision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.CcAgentRepository;
import com.asteriskia.domain.callcenter.CcExtension;
import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.CcQueueRepository;
import com.asteriskia.domain.callcenter.interaction.AgentState;
import com.asteriskia.domain.callcenter.interaction.CcAgentState;
import com.asteriskia.domain.callcenter.interaction.CcAgentStateRepository;
import com.asteriskia.domain.callcenter.interaction.CcInteraction;
import com.asteriskia.domain.callcenter.interaction.CcInteractionRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * CallCenterSupervisionPanelServiceTest — cálculo das estatísticas do painel de supervisão
 * (Fase 6): contagem em espera, maior espera, nível de serviço do dia, tempo no estado atual.
 */
@ExtendWith(MockitoExtension.class)
class CallCenterSupervisionPanelServiceTest {

    @Mock private CcQueueRepository queueRepository;
    @Mock private CcAgentRepository agentRepository;
    @Mock private CcInteractionRepository interactionRepository;
    @Mock private CcAgentStateRepository agentStateRepository;
    @Mock private AmiQueueStatusClient amiQueueStatusClient;

    private CallCenterSupervisionPanelService newService() {
        return new CallCenterSupervisionPanelService(
                queueRepository, agentRepository, interactionRepository, agentStateRepository, amiQueueStatusClient);
    }

    private CcQueue queue(Long id, int timeoutSeconds) {
        return CcQueue.builder().id(id).name("500" + id).displayName("Fila " + id).timeoutSeconds(timeoutSeconds).build();
    }

    @Test
    @DisplayName("snapshot conta chamadas em espera e ignora atendidas/abandonadas")
    void snapshot_countsWaitingCalls() {
        var service = newService();
        var q = queue(1L, 15);
        when(queueRepository.findAll()).thenReturn(List.of(q));
        when(agentRepository.findAll()).thenReturn(List.of());

        var waiting = CcInteraction.builder().queue(q).queuedAt(LocalDateTime.now().minusSeconds(30)).build();
        var answered =
                CcInteraction.builder()
                        .queue(q)
                        .queuedAt(LocalDateTime.now().minusSeconds(40))
                        .answeredAt(LocalDateTime.now().minusSeconds(30))
                        .build();
        when(interactionRepository.findByQueueIdAndQueuedAtAfter(eq(1L), ArgumentMatchers.any()))
                .thenReturn(List.of(waiting, answered));

        var snapshot = service.snapshot();

        assertThat(snapshot.queues()).hasSize(1);
        var view = snapshot.queues().get(0);
        assertThat(view.waitingCount()).isEqualTo(1);
        assertThat(view.answeredToday()).isEqualTo(1);
        assertThat(view.longestWaitSeconds()).isGreaterThanOrEqualTo(30);
    }

    @Test
    @DisplayName("nível de serviço considera o timeout configurado da fila")
    void snapshot_serviceLevel_respectsQueueTimeout() {
        var service = newService();
        var q = queue(1L, 10);
        when(queueRepository.findAll()).thenReturn(List.of(q));
        when(agentRepository.findAll()).thenReturn(List.of());

        var withinTimeout =
                CcInteraction.builder()
                        .queue(q)
                        .queuedAt(LocalDateTime.now().minusSeconds(20))
                        .answeredAt(LocalDateTime.now().minusSeconds(15))
                        .build();
        var beyondTimeout =
                CcInteraction.builder()
                        .queue(q)
                        .queuedAt(LocalDateTime.now().minusSeconds(50))
                        .answeredAt(LocalDateTime.now().minusSeconds(10))
                        .build();
        when(interactionRepository.findByQueueIdAndQueuedAtAfter(eq(1L), ArgumentMatchers.any()))
                .thenReturn(List.of(withinTimeout, beyondTimeout));

        var view = service.snapshot().queues().get(0);

        assertThat(view.serviceLevelPercent()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("agente sem estado aberto aparece com state null e secondsInState null")
    void snapshot_agentWithoutOpenState_showsNulls() {
        var service = newService();
        when(queueRepository.findAll()).thenReturn(List.of());
        var agent = CcAgent.builder().id(1L).name("Agente Teste").build();
        agent.setExtension(CcExtension.builder().agent(agent).extension("4001").secret("x").build());
        when(agentRepository.findAll()).thenReturn(List.of(agent));
        when(agentStateRepository.findByAgentIdAndEndedAtIsNull(1L)).thenReturn(Optional.empty());
        when(interactionRepository.countByAgentIdAndAnsweredAtAfter(eq(1L), ArgumentMatchers.any())).thenReturn(0L);

        var view = service.snapshot().agents().get(0);

        assertThat(view.state()).isNull();
        assertThat(view.secondsInState()).isNull();
        assertThat(view.extension()).isEqualTo("4001");
    }

    @Test
    @DisplayName("agente em atendimento mostra o estado atual e o tempo desde que entrou nele")
    void snapshot_agentWithOpenState_showsStateAndDuration() {
        var service = newService();
        when(queueRepository.findAll()).thenReturn(List.of());
        var agent = CcAgent.builder().id(1L).name("Agente Teste").build();
        when(agentRepository.findAll()).thenReturn(List.of(agent));
        var openState =
                CcAgentState.builder()
                        .agent(agent)
                        .state(AgentState.EM_ATENDIMENTO)
                        .startedAt(LocalDateTime.now().minusSeconds(60))
                        .build();
        when(agentStateRepository.findByAgentIdAndEndedAtIsNull(1L)).thenReturn(Optional.of(openState));
        when(interactionRepository.countByAgentIdAndAnsweredAtAfter(eq(1L), ArgumentMatchers.any())).thenReturn(3L);

        var view = service.snapshot().agents().get(0);

        assertThat(view.state()).isEqualTo(AgentState.EM_ATENDIMENTO);
        assertThat(view.secondsInState()).isGreaterThanOrEqualTo(60);
        assertThat(view.answeredToday()).isEqualTo(3);
    }

    @Test
    @DisplayName("waitingCallers vem ordenado por posição, mesmo que o AMI retorne fora de ordem")
    void snapshot_waitingCallers_sortedByPosition() {
        var service = newService();
        var q = queue(1L, 15);
        when(queueRepository.findAll()).thenReturn(List.of(q));
        when(agentRepository.findAll()).thenReturn(List.of());
        when(interactionRepository.findByQueueIdAndQueuedAtAfter(eq(1L), ArgumentMatchers.any()))
                .thenReturn(List.of());
        when(amiQueueStatusClient.queueStatus("5001"))
                .thenReturn(
                        List.of(
                                new WaitingCallerView(2, "222", 30L, "uid-2", "PJSIP/tronco-2"),
                                new WaitingCallerView(1, "111", 10L, "uid-1", "PJSIP/tronco-1")));

        var view = service.snapshot().queues().get(0);

        assertThat(view.waitingCallers()).extracting(WaitingCallerView::channelUniqueId)
                .containsExactly("uid-1", "uid-2");
    }

    @Test
    @DisplayName("waitingCallers vem vazio (não quebra) quando o AMI está indisponível")
    void snapshot_waitingCallers_amiUnavailable_returnsEmpty() {
        var service = newService();
        var q = queue(1L, 15);
        when(queueRepository.findAll()).thenReturn(List.of(q));
        when(agentRepository.findAll()).thenReturn(List.of());
        when(interactionRepository.findByQueueIdAndQueuedAtAfter(eq(1L), ArgumentMatchers.any()))
                .thenReturn(List.of());

        var view = service.snapshot().queues().get(0);

        assertThat(view.waitingCallers()).isEmpty();
    }
}
