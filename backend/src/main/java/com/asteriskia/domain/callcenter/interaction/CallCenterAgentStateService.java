package com.asteriskia.domain.callcenter.interaction;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.CcAgentRepository;
import com.asteriskia.domain.callcenter.CcPauseReasonRepository;
import com.asteriskia.domain.user.AppUserRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CallCenterAgentStateService — motor de transição de estado do agente (Fase 4). Cada transição
 * fecha a linha de {@link CcAgentState} aberta (se houver) e abre uma nova — nunca faz UPDATE no
 * estado em si, só em {@code endedAt} da linha anterior. É a matéria-prima de ocupação/
 * aderência/ACW.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallCenterAgentStateService {

    private final CcAgentStateRepository agentStateRepository;
    private final CcAgentRepository agentRepository;
    private final CcPauseReasonRepository pauseReasonRepository;
    private final AppUserRepository appUserRepository;
    private final SimpMessagingTemplate messagingTemplate;

    /** Resolve o {@link CcAgent} do usuário autenticado (JWT) via app_users.id → cc_agents.user_id. */
    @Transactional(readOnly = true)
    public CcAgent currentAgent() {
        var username = SecurityContextHolder.getContext().getAuthentication().getName();
        var user =
                appUserRepository
                        .findByUsername(username)
                        .orElseThrow(() -> new IllegalStateException("Usuário não encontrado: " + username));
        return agentRepository
                .findByUserId(user.getId())
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "Usuário " + username + " não está vinculado a um agente do Call Center."));
    }

    @Transactional(readOnly = true)
    public AgentStateView currentState() {
        return currentState(currentAgent());
    }

    @Transactional(readOnly = true)
    public AgentStateView currentState(CcAgent agent) {
        return agentStateRepository
                .findByAgentIdAndEndedAtIsNull(agent.getId())
                .map(AgentStateView::from)
                .orElse(new AgentStateView(agent.getId(), AgentState.OFFLINE, null, null));
    }

    /**
     * Transição manual (do próprio agente, via tela de Desktop) ou automática (do
     * {@link CallCenterAmiEventListener}, ao conectar/encerrar uma chamada).
     */
    @Transactional
    public AgentStateView setState(CcAgent agent, AgentState newState, Long pauseReasonId) {
        var pauseReason =
                newState == AgentState.PAUSA
                        ? pauseReasonRepository
                                .findByIdAndActiveTrue(
                                        requireNonNullPauseReason(pauseReasonId))
                                .orElseThrow(
                                        () ->
                                                new IllegalArgumentException(
                                                        "Motivo de pausa inválido: " + pauseReasonId))
                        : null;
        if (newState != AgentState.PAUSA && pauseReasonId != null) {
            throw new IllegalArgumentException("Motivo de pausa só é aceito para o estado PAUSA.");
        }

        var now = LocalDateTime.now();
        agentStateRepository
                .findByAgentIdAndEndedAtIsNull(agent.getId())
                .ifPresent(
                        open -> {
                            open.setEndedAt(now);
                            agentStateRepository.save(open);
                        });

        var created =
                agentStateRepository.save(
                        CcAgentState.builder()
                                .agent(agent)
                                .state(newState)
                                .pauseReason(pauseReason)
                                .startedAt(now)
                                .build());

        var view = AgentStateView.from(created);
        messagingTemplate.convertAndSend("/topic/callcenter/agent-states", view);
        log.info("Estado do agente alterado: agentId={} state={}", agent.getId(), newState);
        return view;
    }

    /** Transição manual disparada pelo próprio agente autenticado. */
    @Transactional
    public AgentStateView setState(AgentStateRequest request) {
        return setState(currentAgent(), request.state(), request.pauseReasonId());
    }

    @Transactional(readOnly = true)
    public List<CcAgentState> currentStatesOfAllAgents() {
        return agentStateRepository.findByEndedAtIsNull();
    }

    private Long requireNonNullPauseReason(Long pauseReasonId) {
        if (pauseReasonId == null) {
            throw new IllegalArgumentException("Motivo de pausa é obrigatório para o estado PAUSA.");
        }
        return pauseReasonId;
    }
}
