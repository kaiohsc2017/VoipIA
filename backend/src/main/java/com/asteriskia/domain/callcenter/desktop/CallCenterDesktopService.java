package com.asteriskia.domain.callcenter.desktop;

import com.asteriskia.domain.callcenter.interaction.AgentState;
import com.asteriskia.domain.callcenter.interaction.CallCenterAgentStateService;
import com.asteriskia.domain.callcenter.interaction.CcAgentState;
import com.asteriskia.domain.callcenter.interaction.CcAgentStateRepository;
import com.asteriskia.domain.callcenter.interaction.CcInteraction;
import com.asteriskia.domain.callcenter.interaction.CcInteractionRepository;
import com.asteriskia.domain.callcenter.recording.CcRecordingRepository;
import com.asteriskia.domain.callcenter.reports.AgentAdherenceRow;
import com.asteriskia.domain.callcenter.reports.AgentGamificationRow;
import com.asteriskia.domain.callcenter.reports.AgentProductivityReport;
import com.asteriskia.domain.callcenter.reports.CallCenterAgentAdherenceService;
import com.asteriskia.domain.callcenter.reports.CallCenterGamificationService;
import com.asteriskia.domain.callcenter.reports.CallCenterProductivityService;
import com.asteriskia.domain.callcenter.reports.CcAggAgentDaily;
import com.asteriskia.domain.callcenter.reports.CcAggAgentDailyRepository;
import com.asteriskia.domain.callcenter.reports.GamificationReport;
import com.asteriskia.domain.insights.CallAudioFile;
import com.asteriskia.domain.insights.CallAudioFileRepository;
import com.asteriskia.domain.insights.CallTranscriptSegment;
import com.asteriskia.domain.insights.CallTranscriptSegmentRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * CallCenterDesktopService — painel pessoal do agente: resumo, histórico, tendências, escala,
 * produtividade, qualidade e ranking do próprio agente (Fase 22 + Desktop Analítico).
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
    private static final int MAX_HISTORY_DAYS = 90;

    private final CallCenterAgentStateService agentStateService;
    private final CcInteractionRepository interactionRepository;
    private final CcAgentStateRepository agentStateRepository;
    private final CcRecordingRepository recordingRepository;
    private final CallAudioFileRepository audioFileRepository;
    private final CallTranscriptSegmentRepository transcriptSegmentRepository;
    private final CcAggAgentDailyRepository aggRepository;
    private final CallCenterAgentAdherenceService adherenceService;
    private final CallCenterProductivityService productivityService;
    private final CallCenterGamificationService gamificationService;
    private final com.asteriskia.domain.callcenter.quality.CallCenterQualityCoachingService qualityCoachingService;

    @Transactional(readOnly = true)
    public DesktopSummaryView resumo() {
        var agent = agentStateService.currentAgent();
        LocalDate today = LocalDate.now();
        var start = today.atStartOfDay();
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
        long talkSecondsTotal = statesSeconds.getOrDefault(AgentState.EM_ATENDIMENTO, 0L);

        Double occupancyPct = loggedSeconds > 0
                ? BigDecimal.valueOf(talkSecondsTotal)
                        .divide(BigDecimal.valueOf(loggedSeconds), 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .doubleValue()
                : 0.0;

        BigDecimal avgNpsScore = calculateAvgNps(answered);

        List<AgentAdherenceRow> adhRows = adherenceService.adherence(agent.getId(), today, today);
        BigDecimal adherencePct = (adhRows != null && !adhRows.isEmpty()) ? adhRows.get(0).adherencePct() : null;

        Double comparedTo7dAvgTalkPct = calculateComparedTo7dAvgTalkPct(agent.getId(), today, avgTalkSeconds);

        return new DesktopSummaryView(
                answered.size(),
                avgTalkSeconds,
                loggedSeconds,
                pauseSeconds,
                occupancyPct,
                avgNpsScore,
                adherencePct,
                comparedTo7dAvgTalkPct);
    }

    @Transactional(readOnly = true)
    public List<DesktopCallHistoryItem> historico() {
        return historico(null, null);
    }

    @Transactional(readOnly = true)
    public List<DesktopCallHistoryItem> historico(LocalDate de, LocalDate ate) {
        var agent = agentStateService.currentAgent();
        LocalDate fromDate = de != null ? de : LocalDate.now();
        LocalDate toDate = ate != null ? ate : LocalDate.now();

        if (toDate.isBefore(fromDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Data final deve ser após a data inicial");
        }
        if (ChronoUnit.DAYS.between(fromDate, toDate) > MAX_HISTORY_DAYS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Janela de busca excede o limite máximo de 90 dias");
        }

        LocalDateTime start = fromDate.atStartOfDay();
        LocalDateTime end = toDate.atTime(LocalTime.MAX);

        return interactionRepository.findByAgentIdAndQueuedAtBetween(agent.getId(), start, end).stream()
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

    @Transactional(readOnly = true)
    public List<DesktopTrendPoint> tendencia(int dias) {
        var agent = agentStateService.currentAgent();
        int window = dias == 30 ? 30 : 7;
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(window - 1);

        List<CcAggAgentDaily> aggRows =
                aggRepository.findByAgentIdAndDateBetweenOrderByDateAsc(agent.getId(), startDate, endDate);
        Map<LocalDate, CcAggAgentDaily> rowsByDate = aggRows.stream()
                .collect(Collectors.toMap(CcAggAgentDaily::getDate, r -> r, (r1, r2) -> r1));

        List<DesktopTrendPoint> points = new ArrayList<>();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            CcAggAgentDaily row = rowsByDate.get(date);
            if (row != null) {
                Double occupancy = null;
                long totalTime = row.getOccupiedSeconds() + row.getAvailableSeconds();
                if (totalTime > 0) {
                    occupancy = BigDecimal.valueOf(row.getOccupiedSeconds())
                            .divide(BigDecimal.valueOf(totalTime), 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100))
                            .doubleValue();
                }
                points.add(new DesktopTrendPoint(
                        date,
                        row.getAnswered(),
                        row.getAvgTalkSeconds() != null ? row.getAvgTalkSeconds().intValue() : null,
                        occupancy,
                        row.getAvgNpsScore()));
            } else {
                points.add(new DesktopTrendPoint(date, 0, null, null, null));
            }
        }
        return points;
    }

    @Transactional(readOnly = true)
    public DesktopScheduleView escala(LocalDate data) {
        var agent = agentStateService.currentAgent();
        LocalDate targetDate = data != null ? data : LocalDate.now();

        List<AgentAdherenceRow> rows = adherenceService.adherence(agent.getId(), targetDate, targetDate);
        if (rows == null || rows.isEmpty() || rows.get(0).scheduledSeconds() == null) {
            return new DesktopScheduleView(
                    "Sem escala cadastrada", null, null, 0L, 0L, null, "SEM_ESCALA");
        }

        AgentAdherenceRow row = rows.get(0);
        LocalDateTime shiftStart = targetDate.atTime(8, 0);
        LocalDateTime shiftEnd = targetDate.atTime(17, 0);

        String status = (row.adherencePct() != null && row.adherencePct().doubleValue() >= 85.0)
                ? "DENTRO_DA_ESCALA"
                : "FORA_DA_ESCALA";

        return new DesktopScheduleView(
                "Escala Normal",
                shiftStart,
                shiftEnd,
                row.scheduledSeconds(),
                row.loggedSeconds(),
                row.adherencePct(),
                status);
    }

    @Transactional(readOnly = true)
    public AgentProductivityReport produtividade(LocalDate de, LocalDate ate) {
        var agent = agentStateService.currentAgent();
        LocalDate fromDate = de != null ? de : LocalDate.now().minusDays(7);
        LocalDate toDate = ate != null ? ate : LocalDate.now();
        return productivityService.build(agent.getId(), fromDate, toDate);
    }

    @Transactional(readOnly = true)
    public DesktopQualityView qualidade(LocalDate de, LocalDate ate) {
        var agent = agentStateService.currentAgent();
        LocalDate fromDate = de != null ? de : LocalDate.now().minusDays(30);
        LocalDate toDate = ate != null ? ate : LocalDate.now();

        AgentProductivityReport prodReport = productivityService.build(agent.getId(), fromDate, toDate);
        Integer totalEval = prodReport.analise() != null ? (int) prodReport.analise().totalChamadas() : 0;
        BigDecimal avgScore = prodReport.analise() != null ? prodReport.analise().notaMedia() : null;

        return new DesktopQualityView(
                totalEval,
                avgScore,
                prodReport.pontosFortes(),
                prodReport.pontosMelhoria());
    }

    @Transactional(readOnly = true)
    public DesktopRankingView ranking(LocalDate de, LocalDate ate) {
        var agent = agentStateService.currentAgent();
        LocalDate fromDate = de != null ? de : LocalDate.now().withDayOfMonth(1);
        LocalDate toDate = ate != null ? ate : LocalDate.now();

        GamificationReport report = gamificationService.rank(fromDate, toDate, 1);
        List<AgentGamificationRow> rankingList = report.ranking();

        int myPosition = -1;
        BigDecimal myNps = null;
        for (AgentGamificationRow row : rankingList) {
            if (Objects.equals(row.agentId(), agent.getId())) {
                myPosition = row.position();
                myNps = row.npsMedio();
                break;
            }
        }

        if (myPosition == -1) {
            myPosition = rankingList.size() + 1;
        }

        String tier = myPosition <= 3 ? "Top Performer" : (myPosition <= 10 ? "Destaque" : "Em Evolução");

        List<DesktopRankingView.AnonymousRankingItem> top3 = new ArrayList<>();
        int limit = Math.min(3, rankingList.size());
        for (int i = 0; i < limit; i++) {
            AgentGamificationRow r = rankingList.get(i);
            top3.add(new DesktopRankingView.AnonymousRankingItem(r.position(), r.npsMedio(), "Agente #" + r.position()));
        }

        return new DesktopRankingView(myPosition, rankingList.size(), myNps, tier, top3);
    }

    @Transactional(readOnly = true)
    public List<com.asteriskia.domain.callcenter.quality.DesktopEvaluationDetailView> avaliacoes(LocalDate de, LocalDate ate) {
        var agent = agentStateService.currentAgent();
        return qualityCoachingService.getEvaluationsForAgent(agent, de, ate);
    }

    @Transactional
    public com.asteriskia.domain.callcenter.quality.AppealView contestarAvaliacao(
            Long evaluationId, com.asteriskia.domain.callcenter.quality.CreateAppealRequest request) {
        var agent = agentStateService.currentAgent();
        return qualityCoachingService.createAppeal(evaluationId, agent, request.reason());
    }

    @Transactional(readOnly = true)
    public List<com.asteriskia.domain.callcenter.quality.CoachingPlanView> coaching() {
        var agent = agentStateService.currentAgent();
        return qualityCoachingService.getCoachingPlansForAgent(agent.getId());
    }

    @Transactional
    public com.asteriskia.domain.callcenter.quality.CoachingPlanView atualizarStatusCoaching(
            Long planId, com.asteriskia.domain.callcenter.quality.UpdateCoachingStatusRequest request) {
        var agent = agentStateService.currentAgent();
        return qualityCoachingService.updateCoachingPlanStatusByAgent(planId, agent.getId(), request.status());
    }

    private DesktopCallHistoryItem toHistoryItem(CcInteraction interaction) {
        Integer talkSeconds = null;
        if (interaction.getAnsweredAt() != null && interaction.getEndedAt() != null) {
            talkSeconds = (int) Duration.between(interaction.getAnsweredAt(), interaction.getEndedAt()).toSeconds();
        }

        Integer waitSeconds = null;
        if (interaction.getQueuedAt() != null && interaction.getAnsweredAt() != null) {
            waitSeconds = (int) Duration.between(interaction.getQueuedAt(), interaction.getAnsweredAt()).toSeconds();
        }

        String dispositionLabel = interaction.getDisposition() != null
                ? interaction.getDisposition().getLabel()
                : null;

        String contactName = interaction.getAni();

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
                waitSeconds,
                0,
                dispositionLabel,
                contactName,
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

    private BigDecimal calculateAvgNps(List<CcInteraction> answered) {
        var withNps = answered.stream().filter(i -> i.getNpsScore() != null).toList();
        if (withNps.isEmpty()) {
            return null;
        }
        double sum = withNps.stream().mapToDouble(i -> i.getNpsScore().doubleValue()).sum();
        return BigDecimal.valueOf(sum / withNps.size()).setScale(2, RoundingMode.HALF_UP);
    }

    private Double calculateComparedTo7dAvgTalkPct(Long agentId, LocalDate today, Integer todayAvgTalkSeconds) {
        if (todayAvgTalkSeconds == null) {
            return null;
        }
        List<CcAggAgentDaily> past7d = aggRepository.findByAgentIdAndDateBetweenOrderByDateAsc(
                agentId, today.minusDays(7), today.minusDays(1));
        var withTalk = past7d.stream().filter(r -> r.getAvgTalkSeconds() != null).toList();
        if (withTalk.isEmpty()) {
            return null;
        }
        double avg7d = withTalk.stream().mapToDouble(r -> r.getAvgTalkSeconds().doubleValue()).average().orElse(0.0);
        if (avg7d == 0.0) {
            return null;
        }
        return ((todayAvgTalkSeconds - avg7d) * 100.0) / avg7d;
    }

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
