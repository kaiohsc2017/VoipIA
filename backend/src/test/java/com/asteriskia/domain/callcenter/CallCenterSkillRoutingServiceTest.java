package com.asteriskia.domain.callcenter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * CallCenterSkillRoutingServiceTest — Fase 5f.1. Cobre: CRUD de skill de agente/fila com nível
 * 1-5, elegibilidade (agente precisa atingir min_level em TODAS as skills exigidas pela fila) e o
 * recálculo de participação explícito (nunca toca em penalty, nunca roda sozinho).
 */
@ExtendWith(MockitoExtension.class)
class CallCenterSkillRoutingServiceTest {

    @Mock private CcAgentSkillRepository agentSkillRepository;
    @Mock private CcQueueSkillRepository queueSkillRepository;
    @Mock private CcSkillRepository skillRepository;
    @Mock private CallCenterAgentService agentService;
    @Mock private CallCenterQueueService queueService;

    private CallCenterSkillRoutingService service;

    private CcAgent agent1;
    private CcAgent agent2;
    private CcQueue queue;
    private CcSkill skillA;
    private CcSkill skillB;

    @BeforeEach
    void setUp() {
        service =
                new CallCenterSkillRoutingService(
                        agentSkillRepository, queueSkillRepository, skillRepository, agentService, queueService);
        agent1 = CcAgent.builder().id(1L).name("Agente 1").build();
        agent2 = CcAgent.builder().id(2L).name("Agente 2").build();
        queue = CcQueue.builder().id(10L).name("5000").displayName("Fila").build();
        skillA = CcSkill.builder().id(100L).name("Suporte N1").build();
        skillB = CcSkill.builder().id(101L).name("Vendas").build();
    }

    @Test
    @DisplayName("assignAgentSkill valida faixa 1-5")
    void assignAgentSkillRejectsOutOfRangeLevel() {
        // Validação de faixa roda antes de qualquer busca (agente/skill) — sem stub necessário.
        assertThatThrownBy(() -> service.assignAgentSkill(1L, 100L, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.assignAgentSkill(1L, 100L, 6))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("assignAgentSkill cria vínculo novo com o nível informado")
    void assignAgentSkillCreatesNew() {
        when(agentService.findById(1L)).thenReturn(agent1);
        when(skillRepository.findById(100L)).thenReturn(Optional.of(skillA));
        when(agentSkillRepository.findByAgentIdAndSkillId(1L, 100L)).thenReturn(Optional.empty());
        when(agentSkillRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));

        var saved = service.assignAgentSkill(1L, 100L, 3);

        assertThat(saved.getLevel()).isEqualTo(3);
        assertThat(saved.getAgent()).isEqualTo(agent1);
        assertThat(saved.getSkill()).isEqualTo(skillA);
    }

    @Test
    @DisplayName("assignAgentSkill atualiza o nível de um vínculo já existente (upsert)")
    void assignAgentSkillUpdatesExisting() {
        var existing = CcAgentSkill.builder().agent(agent1).skill(skillA).level(1).build();
        when(agentService.findById(1L)).thenReturn(agent1);
        when(skillRepository.findById(100L)).thenReturn(Optional.of(skillA));
        when(agentSkillRepository.findByAgentIdAndSkillId(1L, 100L)).thenReturn(Optional.of(existing));
        when(agentSkillRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));

        var saved = service.assignAgentSkill(1L, 100L, 4);

        assertThat(saved.getLevel()).isEqualTo(4);
        verify(agentSkillRepository, times(1)).save(existing);
    }

    @Test
    @DisplayName("setQueueRequiredSkill valida faixa 1-5 e faz upsert do min_level")
    void setQueueRequiredSkillValidatesAndUpserts() {
        when(queueService.findById(10L)).thenReturn(queue);
        when(skillRepository.findById(100L)).thenReturn(Optional.of(skillA));
        when(queueSkillRepository.findByQueueIdAndSkillId(10L, 100L)).thenReturn(Optional.empty());
        when(queueSkillRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> service.setQueueRequiredSkill(10L, 100L, 0))
                .isInstanceOf(IllegalArgumentException.class);

        var saved = service.setQueueRequiredSkill(10L, 100L, 5);
        assertThat(saved.getMinLevel()).isEqualTo(5);
    }

    @Test
    @DisplayName("recalculateQueueMembership: sem skill exigida pela fila, não mexe em nada")
    void recalculateNoRequiredSkillsIsNoOp() {
        when(queueService.findById(10L)).thenReturn(queue);
        when(queueSkillRepository.findByQueueId(10L)).thenReturn(List.of());

        var result = service.recalculateQueueMembership(10L);

        assertThat(result.added()).isZero();
        assertThat(result.removed()).isZero();
        verify(queueService, never()).addMember(anyLong(), anyLong());
        verify(queueService, never()).removeMember(anyLong(), anyLong());
    }

    @Test
    @DisplayName("recalculateQueueMembership adiciona agente elegível ausente e remove o inelegível já membro")
    void recalculateAddsEligibleAndRemovesIneligible() {
        var required = List.of(CcQueueSkill.builder().queue(queue).skill(skillA).minLevel(3).build());
        when(queueService.findById(10L)).thenReturn(queue);
        when(queueSkillRepository.findByQueueId(10L)).thenReturn(required);
        when(agentService.findAll()).thenReturn(List.of(agent1, agent2));
        // agent1 já é membro mas não atinge o nível exigido -> deve ser removido.
        // agent2 não é membro mas atinge o nível exigido -> deve ser adicionado.
        when(queueService.members(10L))
                .thenReturn(
                        List.of(CcQueueMember.builder().queue(queue).agent(agent1).penalty(7).build()));
        when(agentSkillRepository.findByAgentIdAndSkillId(1L, 100L))
                .thenReturn(Optional.of(CcAgentSkill.builder().agent(agent1).skill(skillA).level(1).build()));
        when(agentSkillRepository.findByAgentIdAndSkillId(2L, 100L))
                .thenReturn(Optional.of(CcAgentSkill.builder().agent(agent2).skill(skillA).level(5).build()));

        var result = service.recalculateQueueMembership(10L);

        assertThat(result.added()).isEqualTo(1);
        assertThat(result.removed()).isEqualTo(1);
        verify(queueService, times(1)).addMember(10L, 2L);
        verify(queueService, times(1)).removeMember(10L, 1L);
        // Nunca chama updateMemberPenalty — o recálculo de skill jamais toca em prioridade manual.
        verify(queueService, never()).updateMemberPenalty(anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("recalculateQueueMembership não remove agente elegível já membro, e não adiciona de novo quem já está")
    void recalculateLeavesEligibleMembersUntouched() {
        var required = List.of(CcQueueSkill.builder().queue(queue).skill(skillA).minLevel(2).build());
        when(queueService.findById(10L)).thenReturn(queue);
        when(queueSkillRepository.findByQueueId(10L)).thenReturn(required);
        when(agentService.findAll()).thenReturn(List.of(agent1));
        when(queueService.members(10L))
                .thenReturn(
                        List.of(CcQueueMember.builder().queue(queue).agent(agent1).penalty(9).build()));
        when(agentSkillRepository.findByAgentIdAndSkillId(1L, 100L))
                .thenReturn(Optional.of(CcAgentSkill.builder().agent(agent1).skill(skillA).level(2).build()));

        var result = service.recalculateQueueMembership(10L);

        assertThat(result.added()).isZero();
        assertThat(result.removed()).isZero();
        verify(queueService, never()).addMember(anyLong(), anyLong());
        verify(queueService, never()).removeMember(anyLong(), anyLong());
    }

    @Test
    @DisplayName("agente sem o vínculo de skill exigido é tratado como inelegível (nível 0 implícito)")
    void agentWithoutSkillLinkIsIneligible() {
        var required = List.of(CcQueueSkill.builder().queue(queue).skill(skillA).minLevel(1).build());
        when(queueService.findById(10L)).thenReturn(queue);
        when(queueSkillRepository.findByQueueId(10L)).thenReturn(required);
        when(agentService.findAll()).thenReturn(List.of(agent1));
        when(queueService.members(10L)).thenReturn(List.of());
        when(agentSkillRepository.findByAgentIdAndSkillId(1L, 100L)).thenReturn(Optional.empty());

        var result = service.recalculateQueueMembership(10L);

        assertThat(result.added()).isZero();
        verify(queueService, never()).addMember(anyLong(), anyLong());
    }
}
