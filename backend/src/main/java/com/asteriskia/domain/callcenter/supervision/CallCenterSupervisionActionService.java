package com.asteriskia.domain.callcenter.supervision;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.CcAgentRepository;
import com.asteriskia.domain.callcenter.interaction.AgentState;
import com.asteriskia.domain.callcenter.interaction.CallCenterAgentStateService;
import com.asteriskia.domain.user.AppUser;
import com.asteriskia.domain.user.AppUserRepository;
import com.asteriskia.integration.ami.AmiOriginateService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * CallCenterSupervisionActionService — ações do supervisor sobre um agente (Fase 6): escuta,
 * sussurro, interceptação (via ChanSpy originado pelo AMI) e pausa/despausa forçada (reusa
 * {@link CallCenterAgentStateService}, mesmo motor de estado da Fase 4). Toda ação é auditada em
 * {@code cc_supervision_actions}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallCenterSupervisionActionService {

    private static final String LISTEN_OPTIONS = "b";
    private static final String WHISPER_OPTIONS = "bw";
    private static final String BARGE_OPTIONS = "bB";

    private final CcAgentRepository agentRepository;
    private final AppUserRepository appUserRepository;
    private final CcSupervisionActionRepository actionRepository;
    private final AmiOriginateService amiOriginateService;
    private final CallCenterAgentStateService agentStateService;

    @Transactional
    public void listen(Long agentId) {
        performChanSpy(agentId, SupervisionActionType.LISTEN, LISTEN_OPTIONS);
    }

    @Transactional
    public void whisper(Long agentId) {
        performChanSpy(agentId, SupervisionActionType.WHISPER, WHISPER_OPTIONS);
    }

    @Transactional
    public void barge(Long agentId) {
        performChanSpy(agentId, SupervisionActionType.BARGE, BARGE_OPTIONS);
    }

    @Transactional
    public void forcePause(Long agentId, Long pauseReasonId) {
        var agent = findAgent(agentId);
        agentStateService.setState(agent, AgentState.PAUSA, pauseReasonId);
        audit(agent, SupervisionActionType.FORCE_PAUSE, true);
    }

    @Transactional
    public void forceUnpause(Long agentId) {
        var agent = findAgent(agentId);
        agentStateService.setState(agent, AgentState.DISPONIVEL, null);
        audit(agent, SupervisionActionType.FORCE_UNPAUSE, true);
    }

    private void performChanSpy(Long agentId, SupervisionActionType type, String options) {
        var agent = findAgent(agentId);
        var extension =
                agent.getExtension() == null
                        ? null
                        : agent.getExtension().getExtension();
        if (extension == null) {
            throw new IllegalArgumentException("Agente sem ramal provisionado: " + agentId);
        }
        var supervisor = currentSupervisorUser();
        var ok = amiOriginateService.originateChanSpy(String.valueOf(supervisor.getExtension()), extension, options);
        if (!ok) {
            throw new IllegalStateException("Falha ao originar a chamada de monitoria via AMI.");
        }
        audit(agent, type, false);
        log.info(
                "Ação de supervisão originada: supervisorId={} agentId={} tipo={}",
                supervisor.getId(),
                agentId,
                type);
    }

    private void audit(CcAgent agent, SupervisionActionType type, boolean instantaneous) {
        var now = LocalDateTime.now();
        actionRepository.save(
                CcSupervisionAction.builder()
                        .supervisorUserId(currentSupervisorUser().getId())
                        .agent(agent)
                        .actionType(type)
                        .startedAt(now)
                        .endedAt(instantaneous ? now : null)
                        .build());
    }

    /** Achado de bug (mesma revisão que corrigiu {@code CallCenterAgentStateService.currentAgent}):
     * {@code IllegalArgumentException}/{@code IllegalStateException} aqui viravam 500 genérico —
     * trocado por {@link ResponseStatusException} (404), preservando a mensagem para o supervisor. */
    private CcAgent findAgent(Long agentId) {
        return agentRepository
                .findById(agentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agente não encontrado: " + agentId));
    }

    private AppUser currentSupervisorUser() {
        var username = SecurityContextHolder.getContext().getAuthentication().getName();
        return appUserRepository
                .findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não encontrado: " + username));
    }
}
