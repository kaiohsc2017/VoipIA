package com.asteriskia.domain.callcenter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/**
 * Cobre o provisionamento de atendente a partir do cadastro de usuário (Fase 12.1 — o
 * desbloqueador de toda validação real do módulo). Não testa o provisionamento ARA em si (já
 * coberto por {@code CallCenterAgentServiceTest}) — aqui o foco é a orquestração: alocação de
 * ramal, idempotência por usuário, e desativação que preserva o histórico.
 */
@ExtendWith(MockitoExtension.class)
class CallCenterAgentProvisioningServiceTest {

    @Mock private CcExtensionRepository extensionRepository;
    @Mock private CallCenterAgentService agentService;
    @Mock private CallCenterQueueService queueService;
    @Mock private CcAgentRepository agentRepository;

    private CallCenterAgentProvisioningService service() {
        return new CallCenterAgentProvisioningService(extensionRepository, agentService, queueService, agentRepository);
    }

    @Test
    @DisplayName("provisionForUser aloca o próximo ramal livre e cria o agente")
    void provisionForUser_allocatesExtensionAndCreatesAgent() {
        when(agentRepository.findByUserId(10)).thenReturn(Optional.empty());
        when(extensionRepository.findNextExtension(
                        CallCenterAgentService.RANGE_START, CallCenterAgentService.RANGE_END))
                .thenReturn(4001);
        var createdAgent = CcAgent.builder().id(99L).name("Fulano").build();
        when(agentService.create(any())).thenReturn(createdAgent);

        var service = service();
        var result = service.provisionForUser(10, "Fulano", 1, List.of());

        assertThat(result.getId()).isEqualTo(99L);
        var captor = org.mockito.ArgumentCaptor.forClass(AgentRequest.class);
        verify(agentService).create(captor.capture());
        assertThat(captor.getValue().extension()).isEqualTo("4001");
        assertThat(captor.getValue().userId()).isEqualTo(10);
    }

    @Test
    @DisplayName("provisionForUser insere o agente em cada fila com a prioridade informada")
    void provisionForUser_addsToEachQueueWithPriority() {
        when(agentRepository.findByUserId(10)).thenReturn(Optional.empty());
        when(extensionRepository.findNextExtension(
                        CallCenterAgentService.RANGE_START, CallCenterAgentService.RANGE_END))
                .thenReturn(4001);
        var createdAgent = CcAgent.builder().id(99L).build();
        when(agentService.create(any())).thenReturn(createdAgent);

        var service = service();
        service.provisionForUser(
                10, "Fulano", 1,
                List.of(new QueueMembershipRequest(5L, 2), new QueueMembershipRequest(6L, null)));

        verify(queueService).addMember(5L, 99L, 2);
        verify(queueService).addMember(6L, 99L, 0);
    }

    @Test
    @DisplayName("provisionForUser falha com erro claro se o usuário já possui um agente")
    void provisionForUser_userAlreadyHasAgent_throwsConflict() {
        when(agentRepository.findByUserId(10))
                .thenReturn(Optional.of(CcAgent.builder().id(5L).build()));

        var service = service();
        assertThatThrownBy(() -> service.provisionForUser(10, "Fulano", 1, List.of()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("já possui um agente");

        verify(agentService, never()).create(any());
    }

    @Test
    @DisplayName("provisionForUser falha com erro claro se a faixa de ramais estiver esgotada")
    void provisionForUser_extensionRangeExhausted_throwsConflict() {
        when(agentRepository.findByUserId(10)).thenReturn(Optional.empty());
        when(extensionRepository.findNextExtension(
                        CallCenterAgentService.RANGE_START, CallCenterAgentService.RANGE_END))
                .thenReturn(null);

        var service = service();
        assertThatThrownBy(() -> service.provisionForUser(10, "Fulano", 1, List.of()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("esgotada");

        verify(agentService, never()).create(any());
    }

    @Test
    @DisplayName("provisionForUser converte falha do addMember em erro claro (400), não deixa cair no catch-all")
    void provisionForUser_queueFailure_convertsToResponseStatusException() {
        when(agentRepository.findByUserId(10)).thenReturn(Optional.empty());
        when(extensionRepository.findNextExtension(
                        CallCenterAgentService.RANGE_START, CallCenterAgentService.RANGE_END))
                .thenReturn(4001);
        when(agentService.create(any())).thenReturn(CcAgent.builder().id(99L).build());
        when(queueService.addMember(eq(999L), eq(99L), any(Integer.class)))
                .thenThrow(new IllegalArgumentException("Fila não encontrada: 999"));

        var service = service();
        assertThatThrownBy(
                        () ->
                                service.provisionForUser(
                                        10, "Fulano", 1, List.of(new QueueMembershipRequest(999L, 0))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Fila não encontrada");
    }

    @Test
    @DisplayName("deactivateForUser remove o agente de todas as filas e o desativa, sem apagar a linha")
    void deactivateForUser_removesFromQueuesAndDeactivates() {
        var agent = CcAgent.builder().id(5L).active(true).build();
        when(agentRepository.findByUserId(10)).thenReturn(Optional.of(agent));

        service().deactivateForUser(10);

        verify(queueService).removeFromAllQueues(5L);
        assertThat(agent.getActive()).isFalse();
        verify(agentRepository).save(agent);
    }

    @Test
    @DisplayName("deactivateForUser é sem-op quando o usuário não tem agente vinculado")
    void deactivateForUser_noAgent_isNoOp() {
        when(agentRepository.findByUserId(10)).thenReturn(Optional.empty());

        service().deactivateForUser(10);

        verify(queueService, never()).removeFromAllQueues(any());
        verify(agentRepository, never()).save(any());
    }
}
