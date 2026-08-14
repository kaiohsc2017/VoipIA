package com.asteriskia.domain.callcenter;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * CallCenterSkillRoutingService — roteamento por skill (Fase 5f.1 do plano
 * callcenter-fases-5-7-9.plan.md).
 *
 * <p><b>Tradução honesta (documentada aqui, não só no plano):</b> o {@code app_queue} do
 * Asterisk não conhece o conceito de "skill" — a única coisa que ele lê de verdade é
 * {@code penalty}/pertencimento em {@code queue_members} (ARA). Este serviço nunca inventa uma
 * skill dentro do Asterisk: ele decide, em Java, QUEM pode estar em {@code cc_queue_members}
 * (e portanto em {@code queue_members} ARA, espelhado por {@link CallCenterQueueService}) — a
 * skill vira, no final das contas, só "é membro ou não é".
 *
 * <p><b>Regra de precedência skill × prioridade manual (Fase 12), decidida nesta fatia:</b> a
 * prioridade manual (campo {@code penalty} de {@code cc_queue_members}, configurada pelo
 * supervisor na tela de fila/agente) é e continua sendo 100% a fonte de verdade — este serviço
 * NUNCA lê nem escreve {@code penalty} em nenhuma circunstância. A skill decide exclusivamente
 * ELEGIBILIDADE: um agente só pode ser membro de uma fila se atingir o {@code minLevel} de TODAS
 * as skills exigidas por ela (fila sem skill exigida = sem restrição de skill, comportamento
 * idêntico ao anterior a esta fase). Ao incluir um agente elegível que ainda não era membro, a
 * prioridade inicial é sempre 0 (mesmo default já usado por {@code addMember} sem penalty
 * explícito) — depois disso, ajustar a prioridade é 100% manual, como sempre foi.
 *
 * <p><b>Nunca silencioso:</b> {@link #recalculateQueueMembership} — a única operação que
 * adiciona/remove membro por causa de skill — só roda quando o operador aciona explicitamente o
 * endpoint dedicado (botão "Recalcular participação" na tela de fila). Não existe nenhum
 * scheduler/job de background chamando este método: cadastrar uma skill nova com min_level alto,
 * ou reduzir o nível de um agente, NUNCA remove ninguém de fila sozinho — só na próxima vez que
 * alguém pedir explicitamente o recálculo.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallCenterSkillRoutingService {

    private static final int MIN_LEVEL = 1;
    private static final int MAX_LEVEL = 5;

    private final CcAgentSkillRepository agentSkillRepository;
    private final CcQueueSkillRepository queueSkillRepository;
    private final CcSkillRepository skillRepository;
    private final CallCenterAgentService agentService;
    private final CallCenterQueueService queueService;

    /** Resultado do recálculo explícito de participação — exibido na tela de fila. */
    public record RecalculationResult(int added, int removed) {}

    @Transactional(readOnly = true)
    public List<CcAgentSkill> agentSkills(Long agentId) {
        agentService.findById(agentId); // aplica o mesmo guard de BU do resto do domínio de agente
        return agentSkillRepository.findByAgentId(agentId);
    }

    @Transactional(readOnly = true)
    public List<CcQueueSkill> queueSkills(Long queueId) {
        queueService.findById(queueId); // aplica o mesmo guard de BU do resto do domínio de fila
        return queueSkillRepository.findByQueueId(queueId);
    }

    /** Upsert do vínculo agente↔skill com nível (1-5). */
    @Transactional
    public CcAgentSkill assignAgentSkill(Long agentId, Long skillId, int level) {
        validateLevel(level);
        var agent = agentService.findById(agentId);
        var skill = findSkill(skillId);
        var link =
                agentSkillRepository
                        .findByAgentIdAndSkillId(agentId, skillId)
                        .orElseGet(() -> CcAgentSkill.builder().agent(agent).skill(skill).build());
        link.setLevel(level);
        return agentSkillRepository.save(link);
    }

    @Transactional
    public void removeAgentSkill(Long agentId, Long skillId) {
        agentService.findById(agentId);
        agentSkillRepository.deleteByAgentIdAndSkillId(agentId, skillId);
    }

    /** Upsert da skill exigida por uma fila, com nível mínimo (1-5). */
    @Transactional
    public CcQueueSkill setQueueRequiredSkill(Long queueId, Long skillId, int minLevel) {
        validateLevel(minLevel);
        var queue = queueService.findById(queueId);
        var skill = findSkill(skillId);
        var link =
                queueSkillRepository
                        .findByQueueIdAndSkillId(queueId, skillId)
                        .orElseGet(() -> CcQueueSkill.builder().queue(queue).skill(skill).build());
        link.setMinLevel(minLevel);
        return queueSkillRepository.save(link);
    }

    @Transactional
    public void removeQueueRequiredSkill(Long queueId, Long skillId) {
        queueService.findById(queueId);
        queueSkillRepository.deleteByQueueIdAndSkillId(queueId, skillId);
    }

    /**
     * Recalcula a participação de uma fila conforme as skills exigidas — ação explícita, nunca
     * automática (ver javadoc da classe). Fila sem nenhuma skill exigida é no-op: skill não
     * restringe nada nela, então não há o que recalcular.
     */
    @Transactional
    public RecalculationResult recalculateQueueMembership(Long queueId) {
        queueService.findById(queueId); // guard de BU
        var requiredSkills = queueSkillRepository.findByQueueId(queueId);
        if (requiredSkills.isEmpty()) {
            return new RecalculationResult(0, 0);
        }

        var allAgents = agentService.findAll();
        var currentMemberIds =
                queueService.members(queueId).stream()
                        .map(m -> m.getAgent().getId())
                        .collect(java.util.stream.Collectors.toSet());

        int added = 0;
        int removed = 0;
        for (var agent : allAgents) {
            boolean eligible = isEligible(agent.getId(), requiredSkills);
            boolean isMember = currentMemberIds.contains(agent.getId());
            if (eligible && !isMember) {
                queueService.addMember(queueId, agent.getId());
                added++;
            } else if (!eligible && isMember) {
                queueService.removeMember(queueId, agent.getId());
                removed++;
            }
            // eligible && isMember, ou !eligible && !isMember: nada a fazer — e, em nenhum caso,
            // este laço toca em penalty.
        }
        log.info("Recálculo de skill da fila {}: {} adicionado(s), {} removido(s)", queueId, added, removed);
        return new RecalculationResult(added, removed);
    }

    /** Elegível = atinge o minLevel em TODAS as skills exigidas pela fila (ausência de vínculo
     * de skill do agente conta como nível 0 — abaixo de qualquer minLevel válido, 1-5). */
    private boolean isEligible(Long agentId, List<CcQueueSkill> requiredSkills) {
        for (var required : requiredSkills) {
            int agentLevel =
                    agentSkillRepository
                            .findByAgentIdAndSkillId(agentId, required.getSkill().getId())
                            .map(CcAgentSkill::getLevel)
                            .orElse(0);
            if (agentLevel < required.getMinLevel()) {
                return false;
            }
        }
        return true;
    }

    private CcSkill findSkill(Long skillId) {
        return skillRepository
                .findById(skillId)
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Skill não encontrada: " + skillId));
    }

    private void validateLevel(int level) {
        if (level < MIN_LEVEL || level > MAX_LEVEL) {
            throw new IllegalArgumentException(
                    "Nível deve estar entre " + MIN_LEVEL + " e " + MAX_LEVEL + ".");
        }
    }
}
