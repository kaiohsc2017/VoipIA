package com.asteriskia.domain.callcenter.supervision;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.CcAgentRepository;
import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.CcQueueRepository;
import com.asteriskia.domain.callcenter.interaction.AgentState;
import com.asteriskia.domain.callcenter.interaction.CallCenterAgentStateService;
import com.asteriskia.domain.masterdata.BusinessUnitContext;
import com.asteriskia.domain.user.AppUser;
import com.asteriskia.domain.user.AppUserRepository;
import com.asteriskia.integration.ami.AmiOriginateService;
import java.time.LocalDateTime;
import java.util.Objects;
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
    private final CcQueueRepository queueRepository;
    private final AppUserRepository appUserRepository;
    private final CcSupervisionActionRepository actionRepository;
    private final AmiOriginateService amiOriginateService;
    private final CallCenterAgentStateService agentStateService;
    private final AmiQueueStatusClient amiQueueStatusClient;

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
        audit(agent, SupervisionActionType.FORCE_PAUSE, true, null, null);
    }

    @Transactional
    public void forceUnpause(Long agentId) {
        var agent = findAgent(agentId);
        agentStateService.setState(agent, AgentState.DISPONIVEL, null);
        audit(agent, SupervisionActionType.FORCE_UNPAUSE, true, null, null);
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
        var supervisorExtension = resolveSupervisorExtension(supervisor);
        var ok = amiOriginateService.originateChanSpy(supervisorExtension, extension, options);
        if (!ok) {
            throw new IllegalStateException("Falha ao originar a chamada de monitoria via AMI.");
        }
        audit(agent, type, false, null, null);
        log.info(
                "Ação de supervisão originada: supervisorId={} agentId={} tipo={}",
                supervisor.getId(),
                agentId,
                type);
    }

    /** Achado de bug (Fase 15.2): usava sempre {@code AppUser.extension} (faixa 9xxx do
     * softphone WebRTC legado), então um supervisor que também é agente de Call Center (ramal
     * 4xxx próprio, ver Fase 13) ou sem nenhum ramal de usuário cadastrado tinha a monitoria
     * originada para o ramal errado (ou falhava em silêncio no AMI). Prioriza o ramal do
     * {@code CcAgent} vinculado ao usuário; cai para {@code AppUser.extension} só se o
     * supervisor não for agente; falha com mensagem clara se nenhum dos dois existir. */
    private String resolveSupervisorExtension(AppUser supervisor) {
        var supervisorExtension =
                agentRepository
                        .findByUserId(supervisor.getId())
                        .map(CcAgent::getExtension)
                        .map(com.asteriskia.domain.callcenter.CcExtension::getExtension);
        if (supervisorExtension.isPresent()) {
            return supervisorExtension.get();
        }
        if (supervisor.getExtension() != null) {
            return String.valueOf(supervisor.getExtension());
        }
        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Supervisor sem ramal provisionado (nem como agente de Call Center, nem como usuário) — "
                        + "não é possível originar a monitoria.");
    }

    /**
     * Retira o chamador da fila e o redireciona para outra fila (Fase 15.3). O nome do canal é
     * obtido AO VIVO via {@link AmiQueueStatusClient} no instante da ação, não do valor persistido
     * no join — o canal pode ter mudado desde então, e usar o valor antigo seria uma corrida.
     */
    @Transactional
    public void redirectToQueue(String sourceQueueName, String channelUniqueId, Long targetQueueId) {
        var targetQueue = findQueueInScope(targetQueueId);
        var channelName = resolveLiveChannelName(sourceQueueName, channelUniqueId);
        var ok = amiOriginateService.redirectChannel(channelName, "ramais-internos", targetQueue.getName(), 1);
        if (!ok) {
            throw new IllegalStateException("Falha ao redirecionar a chamada via AMI.");
        }
        audit(null, SupervisionActionType.REDIRECT_QUEUE, true, targetQueue, null);
        log.info(
                "Chamada redirecionada para outra fila: supervisorId={} channelUniqueId={} targetQueueId={}",
                currentSupervisorUser().getId(),
                channelUniqueId,
                targetQueueId);
    }

    /** Retira o chamador da fila e o redireciona direto para um agente (Fase 15.3). */
    @Transactional
    public void redirectToAgent(String sourceQueueName, String channelUniqueId, Long targetAgentId) {
        var targetAgent = findAgentInScope(targetAgentId);
        var targetExtension =
                targetAgent.getExtension() == null ? null : targetAgent.getExtension().getExtension();
        if (targetExtension == null) {
            throw new IllegalArgumentException("Agente de destino sem ramal provisionado: " + targetAgentId);
        }
        var channelName = resolveLiveChannelName(sourceQueueName, channelUniqueId);
        var ok = amiOriginateService.redirectChannel(channelName, "ramais-internos", targetExtension, 1);
        if (!ok) {
            throw new IllegalStateException("Falha ao redirecionar a chamada via AMI.");
        }
        audit(null, SupervisionActionType.REDIRECT_AGENT, true, null, targetAgent);
        log.info(
                "Chamada redirecionada para agente: supervisorId={} channelUniqueId={} targetAgentId={}",
                currentSupervisorUser().getId(),
                channelUniqueId,
                targetAgentId);
    }

    private String resolveLiveChannelName(String sourceQueueName, String channelUniqueId) {
        return amiQueueStatusClient.queueStatus(sourceQueueName).stream()
                .filter(w -> channelUniqueId.equals(w.channelUniqueId()))
                .map(WaitingCallerView::channelName)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "Chamador não encontrado na fila — pode já ter sido atendido ou saído."));
    }

    private CcQueue findQueueInScope(Long queueId) {
        var queue =
                queueRepository
                        .findById(queueId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Fila não encontrada: " + queueId));
        if (BusinessUnitContext.isRestricted()
                && queue.getBusinessUnit() != null
                && !BusinessUnitContext.currentBusinessUnitIds().contains(queue.getBusinessUnit().getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Fila não encontrada: " + queueId);
        }
        return queue;
    }

    private CcAgent findAgentInScope(Long agentId) {
        var agent = findAgent(agentId);
        if (BusinessUnitContext.isRestricted()
                && agent.getBusinessUnit() != null
                && !BusinessUnitContext.currentBusinessUnitIds().contains(agent.getBusinessUnit().getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agente não encontrado: " + agentId);
        }
        return agent;
    }

    private void audit(
            CcAgent agent, SupervisionActionType type, boolean instantaneous, CcQueue targetQueue, CcAgent targetAgent) {
        var now = LocalDateTime.now();
        actionRepository.save(
                CcSupervisionAction.builder()
                        .supervisorUserId(currentSupervisorUser().getId())
                        .agent(agent)
                        .actionType(type)
                        .startedAt(now)
                        .endedAt(instantaneous ? now : null)
                        .targetQueue(targetQueue)
                        .targetAgent(targetAgent)
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
