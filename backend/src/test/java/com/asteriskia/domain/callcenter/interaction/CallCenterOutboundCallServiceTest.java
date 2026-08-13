package com.asteriskia.domain.callcenter.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.CcExtension;
import com.asteriskia.domain.callcenter.CcExtensionRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * CallCenterOutboundCallServiceTest — chamada de saída ativo manual (Fase 23): correlação por
 * ramal/UNIQUEID vinda do dialplan (CURL), não de eventos AMI de canal.
 */
@ExtendWith(MockitoExtension.class)
class CallCenterOutboundCallServiceTest {

    @Mock private CcExtensionRepository extensionRepository;
    @Mock private CcInteractionRepository interactionRepository;
    @Mock private CcInteractionEventRepository interactionEventRepository;
    @Mock private CallCenterAgentStateService agentStateService;

    private CallCenterOutboundCallService newService() {
        return new CallCenterOutboundCallService(
                extensionRepository, interactionRepository, interactionEventRepository, agentStateService);
    }

    @Test
    @DisplayName("start cria interação OUTBOUND sem fila e move o agente para EM_ATENDIMENTO")
    void start_agentExtension_createsOutboundInteraction() {
        var agent = CcAgent.builder().id(5L).build();
        when(interactionRepository.existsByChannelUniqueId("uid-1")).thenReturn(false);
        when(extensionRepository.findByExtension("4005"))
                .thenReturn(Optional.of(CcExtension.builder().extension("4005").agent(agent).build()));
        when(interactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        newService().start("uid-1", "4005", "5511999998888");

        var captor = ArgumentCaptor.forClass(CcInteraction.class);
        verify(interactionRepository).save(captor.capture());
        CcInteraction saved = captor.getValue();
        assertThat(saved.getDirection()).isEqualTo(Direction.OUTBOUND);
        assertThat(saved.getQueue()).isNull();
        assertThat(saved.getAgent()).isEqualTo(agent);
        assertThat(saved.getAni()).isEqualTo("5511999998888");
        verify(agentStateService).setState(agent, AgentState.EM_ATENDIMENTO, null);
    }

    @Test
    @DisplayName("start ignora ramal sem agente de Call Center vinculado (ramal de teste discando)")
    void start_extensionWithoutAgent_doesNothing() {
        when(interactionRepository.existsByChannelUniqueId("uid-2")).thenReturn(false);
        when(extensionRepository.findByExtension("1001")).thenReturn(Optional.empty());

        newService().start("uid-2", "1001", "5511999998888");

        verify(interactionRepository, never()).save(any());
        verify(agentStateService, never()).setState(any(), any(), any());
    }

    @Test
    @DisplayName("start ignora UNIQUEID já registrado (retransmissão do CURL)")
    void start_duplicateChannelUniqueId_doesNothing() {
        when(interactionRepository.existsByChannelUniqueId("uid-3")).thenReturn(true);

        newService().start("uid-3", "4005", "5511999998888");

        verify(interactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("end com DIALSTATUS=ANSWER marca answeredAt e devolve o agente para ACW")
    void end_answered_setsAnsweredAtAndAcw() {
        var agent = CcAgent.builder().id(5L).build();
        var interaction = CcInteraction.builder()
                .direction(Direction.OUTBOUND).agent(agent).channelUniqueId("uid-4").build();
        when(interactionRepository.findByChannelUniqueId("uid-4")).thenReturn(Optional.of(interaction));
        when(interactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        newService().end("uid-4", "ANSWER", "42");

        assertThat(interaction.getAnsweredAt()).isNotNull();
        assertThat(interaction.getEndedAt()).isNotNull();
        verify(agentStateService).setState(agent, AgentState.ACW, null);
    }

    @Test
    @DisplayName("end com DIALSTATUS diferente de ANSWER não marca answeredAt e devolve o agente direto para DISPONIVEL")
    void end_notAnswered_skipsAcw() {
        var agent = CcAgent.builder().id(5L).build();
        var interaction = CcInteraction.builder()
                .direction(Direction.OUTBOUND).agent(agent).channelUniqueId("uid-5").build();
        when(interactionRepository.findByChannelUniqueId("uid-5")).thenReturn(Optional.of(interaction));
        when(interactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        newService().end("uid-5", "NOANSWER", null);

        assertThat(interaction.getAnsweredAt()).isNull();
        assertThat(interaction.getEndedAt()).isNotNull();
        verify(agentStateService).setState(agent, AgentState.DISPONIVEL, null);
    }

    @Test
    @DisplayName("end sem interação correspondente (UNIQUEID desconhecido) não quebra e não salva nada")
    void end_unknownChannelUniqueId_doesNothing() {
        when(interactionRepository.findByChannelUniqueId("uid-6")).thenReturn(Optional.empty());

        newService().end("uid-6", "ANSWER", "10");

        verify(interactionRepository, never()).save(any());
        verify(agentStateService, never()).setState(any(), any(), any());
    }
}
