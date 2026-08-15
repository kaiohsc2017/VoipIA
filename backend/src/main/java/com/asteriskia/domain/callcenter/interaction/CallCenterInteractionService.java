package com.asteriskia.domain.callcenter.interaction;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.integration.ad.AdUserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CallCenterInteractionService — leitura da interação em curso do agente (screen pop / painel de
 * atendimento) e aplicação de tabulação ao final da chamada (Fase 4).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallCenterInteractionService {

    private final CcInteractionRepository interactionRepository;
    private final CcDispositionRepository dispositionRepository;
    private final CallCenterAgentStateService agentStateService;
    private final AdUserRepository adUserRepository;

    /** Interação aberta (em atendimento) do agente autenticado, se houver. Fase 14: quando
     * {@code resolvedAdSam} está preenchido, o bloco de identidade é resolvido contra a cópia
     * local do AD ({@code ad_users}) — nunca ao vivo. */
    @Transactional(readOnly = true)
    public InteractionView currentInteraction() {
        var agent = agentStateService.currentAgent();
        return interactionRepository
                .findByAgentIdAndEndedAtIsNull(agent.getId())
                .map(this::toViewWithIdentity)
                .orElse(null);
    }

    /** Histórico de contatos anteriores do mesmo contato identificado (Fase 14, screen pop) —
     * exclui a interação atual. Lista vazia quando a interação atual não tem contato resolvido
     * ou não pertence ao agente autenticado. O {@code resolvedAdSam} usado na busca é sempre o
     * da própria interação carregada do banco — nunca um valor vindo do chamador (IDOR: um
     * {@code resolvedAdSam} arbitrário na query string permitiria a qualquer agente enumerar o
     * histórico e o bloco completo de identidade — nome/e-mail/telefone/cargo — de qualquer
     * contato do AD, não só do próprio atendimento em curso). */
    @Transactional(readOnly = true)
    public List<InteractionView> contactHistory(Long currentInteractionId) {
        var agent = agentStateService.currentAgent();
        var current = interactionRepository.findById(currentInteractionId).orElse(null);
        if (current == null
                || current.getAgent() == null
                || !current.getAgent().getId().equals(agent.getId())
                || current.getResolvedAdSam() == null
                || current.getResolvedAdSam().isBlank()) {
            return List.of();
        }
        return interactionRepository
                .findTop10ByResolvedAdSamAndIdNotOrderByQueuedAtDesc(
                        current.getResolvedAdSam(), currentInteractionId)
                .stream()
                .map(this::toViewWithIdentity)
                .toList();
    }

    private InteractionView toViewWithIdentity(CcInteraction interaction) {
        if (interaction.getResolvedAdSam() == null) {
            return InteractionView.from(interaction);
        }
        var adUser = adUserRepository.findBySamAccountNameIgnoreCase(interaction.getResolvedAdSam()).orElse(null);
        return InteractionView.from(interaction, adUser);
    }

    /**
     * Aplica a tabulação à última interação encerrada e ainda não tabulada do agente autenticado,
     * e encerra o ACW (agente volta para DISPONIVEL). Rejeita se não houver interação pendente de
     * tabulação — evita tabular a chamada errada por uma corrida entre duas abas do mesmo agente.
     */
    @Transactional
    public InteractionView applyDisposition(CcAgent agent, Long dispositionId) {
        var interaction =
                interactionRepository
                        .findFirstByAgentIdAndEndedAtIsNotNullAndDispositionIsNullOrderByEndedAtDesc(
                                agent.getId())
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Nenhuma interação pendente de tabulação para este agente."));
        var disposition =
                dispositionRepository
                        .findById(dispositionId)
                        .filter(CcDisposition::getActive)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Tabulação inválida: " + dispositionId));

        interaction.setDisposition(disposition);
        var saved = interactionRepository.save(interaction);

        agentStateService.setState(agent, AgentState.DISPONIVEL, null);
        log.info(
                "Interação tabulada: interactionId={} agentId={} disposition={}",
                saved.getId(),
                agent.getId(),
                disposition.getCode());
        return InteractionView.from(saved);
    }

    @Transactional
    public InteractionView applyDisposition(DispositionRequest request) {
        return applyDisposition(agentStateService.currentAgent(), request.dispositionId());
    }
}
