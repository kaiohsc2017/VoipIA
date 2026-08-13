package com.asteriskia.domain.callcenter.desktop;

import com.asteriskia.domain.callcenter.interaction.AgentState;
import com.asteriskia.domain.callcenter.interaction.CallCenterAgentStateService;
import com.asteriskia.domain.callcenter.interaction.CcAgentState;
import com.asteriskia.domain.callcenter.interaction.CcAgentStateRepository;
import com.asteriskia.domain.callcenter.interaction.CcInteraction;
import com.asteriskia.domain.callcenter.interaction.CcInteractionRepository;
import com.asteriskia.domain.callcenter.recording.CcRecording;
import com.asteriskia.domain.callcenter.recording.CcRecordingRepository;
import com.asteriskia.domain.insights.CallAudioFile;
import com.asteriskia.domain.insights.CallAudioFileRepository;
import com.asteriskia.domain.insights.CallTranscriptSegment;
import com.asteriskia.domain.insights.CallTranscriptSegmentRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CallCenterDesktopService — painel pessoal do agente: resumo, histórico e pausas do próprio dia
 * (Fase 22 do plano Call Center Parte III).
 *
 * <p><b>Regra fechada (D21):</b> {@link #historico()} é SOMENTE LEITURA de artefato já existente
 * do pipeline de Insights — esta classe nem sequer depende de {@code InsightsIngestionService}
 * (não enfileira, não dispara, não reprocessa nada). Uma chamada gravada mas ainda não processada
 * aparece com {@code transcriptionStatus = EM_PROCESSAMENTO}; nenhuma ação é oferecida a partir
 * daqui — o disparo de processamento continua exclusivo das telas de Processamento do Insights.
 *
 * <p><b>Escopo rígido:</b> todo método resolve o agente por {@link
 * CallCenterAgentStateService#currentAgent()} — nenhum aceita um id de agente vindo do chamador.
 */
@Service
@RequiredArgsConstructor
public class CallCenterDesktopService {

    private static final String STATUS_SEM_GRAVACAO = "SEM_GRAVACAO";
    private static final String STATUS_EM_PROCESSAMENTO = "EM_PROCESSAMENTO";
    private static final String STATUS_DISPONIVEL = "DISPONIVEL";

    private final CallCenterAgentStateService agentStateService;
    private final CcInteractionRepository interactionRepository;
    private final CcAgentStateRepository agentStateRepository;
    private final CcRecordingRepository recordingRepository;
    private final CallAudioFileRepository audioFileRepository;
    private final CallTranscriptSegmentRepository transcriptSegmentRepository;

    @Transactional(readOnly = true)
    public DesktopSummaryView resumo() {
        var agent = agentStateService.currentAgent();
        var start = startOfToday();
        var now = LocalDateTime.now();

        var interactions = interactionRepository.findByAgentIdAndQueuedAtBetween(agent.getId(), start, now);
        var answered = interactions.stream().filter(i -> i.getAnsweredAt() != null).toList();
        Integer avgTalkSeconds = averageTalkSeconds(answered);

        var statesSeconds = secondsInEachState(agent.getId(), start, now);
        long loggedSeconds = statesSeconds.entrySet().stream()
                .filter(e -> e.getKey() != AgentState.OFFLINE)
                .mapToLong(Map.Entry::getValue)
                .sum();
        long pauseSeconds = statesSeconds.getOrDefault(AgentState.PAUSA, 0L);

        return new DesktopSummaryView(answered.size(), avgTalkSeconds, loggedSeconds, pauseSeconds);
    }

    @Transactional(readOnly = true)
    public List<DesktopCallHistoryItem> historico() {
        var agent = agentStateService.currentAgent();
        var start = startOfToday();
        var now = LocalDateTime.now();

        return interactionRepository.findByAgentIdAndQueuedAtBetween(agent.getId(), start, now).stream()
                .sorted(Comparator.comparing(CcInteraction::getQueuedAt).reversed())
                .map(this::toHistoryItem)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DesktopPauseItem> pausas() {
        var agent = agentStateService.currentAgent();
        var start = startOfToday();
        var now = LocalDateTime.now();

        return agentStateRepository.findOverlapping(agent.getId(), start, now).stream()
                .filter(s -> s.getState() == AgentState.PAUSA)
                .sorted(Comparator.comparing(CcAgentState::getStartedAt))
                .map(s -> toPauseItem(s, start, now))
                .toList();
    }

    private DesktopCallHistoryItem toHistoryItem(CcInteraction interaction) {
        Integer talkSeconds = null;
        if (interaction.getAnsweredAt() != null && interaction.getEndedAt() != null) {
            talkSeconds = (int) Duration.between(interaction.getAnsweredAt(), interaction.getEndedAt()).toSeconds();
        }

        var recordingOpt = recordingRepository.findByInteractionId(interaction.getId());
        String recordingUrl = recordingOpt
                .map(r -> "/callcenter/recordings/" + r.getId() + "/audio")
                .orElse(null);

        String transcriptionStatus = STATUS_SEM_GRAVACAO;
        String transcript = null;
        if (recordingOpt.isPresent()) {
            var audioFileOpt = audioFileRepository.findByCcRecordingId(recordingOpt.get().getId());
            if (audioFileOpt.isEmpty() || !"done".equals(audioFileOpt.get().getStatus())) {
                transcriptionStatus = STATUS_EM_PROCESSAMENTO;
            } else {
                transcriptionStatus = STATUS_DISPONIVEL;
                transcript = joinTranscript(audioFileOpt.get());
            }
        }

        return new DesktopCallHistoryItem(
                interaction.getId(),
                interaction.getQueuedAt(),
                interaction.getDirection(),
                interaction.getAni(),
                interaction.getQueue() != null ? interaction.getQueue().getName() : null,
                talkSeconds,
                interaction.getNpsScore(),
                recordingUrl,
                transcriptionStatus,
                transcript);
    }

    private String joinTranscript(CallAudioFile audioFile) {
        List<CallTranscriptSegment> segments =
                transcriptSegmentRepository.findByAudioFileIdOrderByStartMsAsc(audioFile.getId());
        return segments.stream()
                .map(s -> s.getSpeaker() + ": " + s.getText())
                .collect(Collectors.joining("\n"));
    }

    private DesktopPauseItem toPauseItem(CcAgentState state, LocalDateTime dayStart, LocalDateTime now) {
        var end = state.getEndedAt();
        var clippedStart = state.getStartedAt().isAfter(dayStart) ? state.getStartedAt() : dayStart;
        var clippedEnd = end != null ? end : now;
        long durationSeconds = Duration.between(clippedStart, clippedEnd).toSeconds();
        return new DesktopPauseItem(
                state.getPauseReason() != null ? state.getPauseReason().getLabel() : "—",
                state.getStartedAt(),
                end,
                durationSeconds);
    }

    private Integer averageTalkSeconds(List<CcInteraction> answered) {
        var withDuration = answered.stream().filter(i -> i.getEndedAt() != null).toList();
        if (withDuration.isEmpty()) {
            return null;
        }
        long totalSeconds = withDuration.stream()
                .mapToLong(i -> Duration.between(i.getAnsweredAt(), i.getEndedAt()).toSeconds())
                .sum();
        return (int) (totalSeconds / withDuration.size());
    }

    /** Mesmo algoritmo de recorte de {@code CallCenterAgentAggregationService} (Fase 9b), aqui
     * aplicado a "hoje até agora" em vez de um dia fechado. */
    private Map<AgentState, Long> secondsInEachState(
            Long agentId, LocalDateTime windowStart, LocalDateTime windowEnd) {
        Map<AgentState, Long> totals = new EnumMap<>(AgentState.class);
        for (CcAgentState period : agentStateRepository.findOverlapping(agentId, windowStart, windowEnd)) {
            var periodEnd = period.getEndedAt() != null ? period.getEndedAt() : windowEnd;
            var overlapStart = period.getStartedAt().isAfter(windowStart) ? period.getStartedAt() : windowStart;
            var overlapEnd = periodEnd.isBefore(windowEnd) ? periodEnd : windowEnd;
            if (overlapEnd.isAfter(overlapStart)) {
                totals.merge(period.getState(), Duration.between(overlapStart, overlapEnd).toSeconds(), Long::sum);
            }
        }
        return totals;
    }

    private LocalDateTime startOfToday() {
        return LocalDate.now().atStartOfDay();
    }
}
