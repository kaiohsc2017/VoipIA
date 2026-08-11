package com.asteriskia.domain.callcenter.interaction;

import com.asteriskia.domain.callcenter.CcAgent;
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

    /** Interação aberta (em atendimento) do agente autenticado, se houver. */
    @Transactional(readOnly = true)
    public InteractionView currentInteraction() {
        var agent = agentStateService.currentAgent();
        return interactionRepository
                .findByAgentIdAndEndedAtIsNull(agent.getId())
                .map(InteractionView::from)
                .orElse(null);
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
