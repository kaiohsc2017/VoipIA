package com.asteriskia.domain.callcenter.supervision;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.CcAgentRepository;
import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.CcQueueRepository;
import com.asteriskia.domain.callcenter.interaction.CcAgentState;
import com.asteriskia.domain.callcenter.interaction.CcAgentStateRepository;
import com.asteriskia.domain.callcenter.interaction.CcInteraction;
import com.asteriskia.domain.callcenter.interaction.CcInteractionRepository;
import com.asteriskia.domain.masterdata.BusinessUnitContext;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CallCenterSupervisionPanelService — estatísticas em tempo (quase) real de filas e agentes
 * (Fase 6). Computa tudo em memória a partir de {@code cc_interactions}/{@code cc_agent_states}
 * do dia corrente — no volume atual do projeto (fila real ainda não validada, ver
 * {@code CallCenterAmiEventListener}) isso é mais simples e testável que SQL agregado, e
 * revisitável quando o volume real justificar.
 */
@Service
@RequiredArgsConstructor
public class CallCenterSupervisionPanelService {

    private final CcQueueRepository queueRepository;
    private final CcAgentRepository agentRepository;
    private final CcInteractionRepository interactionRepository;
    private final CcAgentStateRepository agentStateRepository;
    private final AmiQueueStatusClient amiQueueStatusClient;

    @Transactional(readOnly = true)
    public SupervisionSnapshot snapshot() {
        var todayStart = LocalDate.now().atStartOfDay();
        var queues = queuesInScope().stream().map(q -> buildQueueView(q, todayStart)).toList();
        var agents = agentsInScope().stream().map(a -> buildAgentView(a, todayStart)).toList();
        return new SupervisionSnapshot(queues, agents);
    }

    private QueueSupervisionView buildQueueView(CcQueue queue, LocalDateTime todayStart) {
        List<CcInteraction> today = interactionRepository.findByQueueIdAndQueuedAtAfter(queue.getId(), todayStart);
        var waiting = today.stream().filter(i -> i.getAnsweredAt() == null && i.getEndedAt() == null).toList();
        var answered = today.stream().filter(i -> i.getAnsweredAt() != null).toList();
        var abandoned = today.stream().filter(i -> i.getAnsweredAt() == null && i.getEndedAt() != null).toList();

        Long longestWaitSeconds =
                waiting.stream()
                        .map(i -> Duration.between(i.getQueuedAt(), LocalDateTime.now()).getSeconds())
                        .max(Long::compareTo)
                        .orElse(null);

        Double serviceLevel = null;
        if (!answered.isEmpty()) {
            var timeout = queue.getTimeoutSeconds() == null ? 15 : queue.getTimeoutSeconds();
            long withinTimeout =
                    answered.stream()
                            .filter(
                                    i ->
                                            Duration.between(i.getQueuedAt(), i.getAnsweredAt()).getSeconds()
                                                    <= timeout)
                            .count();
            serviceLevel = (withinTimeout * 100.0) / answered.size();
        }

        var waitingCallers =
                amiQueueStatusClient.queueStatus(queue.getName()).stream()
                        .sorted(java.util.Comparator.comparing(
                                WaitingCallerView::position, java.util.Comparator.nullsLast(Integer::compareTo)))
                        .toList();

        return new QueueSupervisionView(
                queue.getId(),
                queue.getName(),
                queue.getDisplayName(),
                waiting.size(),
                longestWaitSeconds,
                answered.size(),
                abandoned.size(),
                serviceLevel,
                waitingCallers);
    }

    private AgentSupervisionView buildAgentView(CcAgent agent, LocalDateTime todayStart) {
        var openState = agentStateRepository.findByAgentIdAndEndedAtIsNull(agent.getId()).orElse(null);
        var answeredToday = interactionRepository.countByAgentIdAndAnsweredAtAfter(agent.getId(), todayStart);
        return new AgentSupervisionView(
                agent.getId(),
                agent.getName(),
                agent.getExtension() == null ? null : agent.getExtension().getExtension(),
                openState == null ? null : openState.getState(),
                openState == null || openState.getPauseReason() == null ? null : openState.getPauseReason().getLabel(),
                secondsInState(openState),
                (int) answeredToday);
    }

    private Long secondsInState(CcAgentState state) {
        if (state == null) {
            return null;
        }
        return Duration.between(state.getStartedAt(), LocalDateTime.now()).getSeconds();
    }

    private List<CcQueue> queuesInScope() {
        if (!BusinessUnitContext.isRestricted()) {
            return queueRepository.findAll();
        }
        var allowed = BusinessUnitContext.currentBusinessUnitIds();
        return queueRepository.findAll().stream().filter(q -> inScope(q.getBusinessUnit(), allowed)).toList();
    }

    private List<CcAgent> agentsInScope() {
        if (!BusinessUnitContext.isRestricted()) {
            return agentRepository.findAll();
        }
        var allowed = BusinessUnitContext.currentBusinessUnitIds();
        return agentRepository.findAll().stream().filter(a -> inScope(a.getBusinessUnit(), allowed)).toList();
    }

    private boolean inScope(com.asteriskia.domain.masterdata.BusinessUnit businessUnit, Set<Integer> allowedIds) {
        return businessUnit == null || allowedIds.contains(businessUnit.getId());
    }
}
