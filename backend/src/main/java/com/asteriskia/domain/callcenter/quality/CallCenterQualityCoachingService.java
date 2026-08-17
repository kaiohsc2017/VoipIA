package com.asteriskia.domain.callcenter.quality;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.CcAgentRepository;
import com.asteriskia.domain.callcenter.interaction.CcInteraction;
import com.asteriskia.domain.callcenter.interaction.CcInteractionRepository;
import com.asteriskia.domain.callcenter.recording.CcRecording;
import com.asteriskia.domain.callcenter.recording.CcRecordingRepository;
import com.asteriskia.domain.insights.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Slf4j
public class CallCenterQualityCoachingService {

    private final CcEvaluationAppealRepository appealRepository;
    private final CcAgentCoachingPlanRepository coachingPlanRepository;
    private final CallEvaluationRepository evaluationRepository;
    private final CallEvaluationItemRepository evaluationItemRepository;
    private final QualityScorecardRepository scorecardRepository;
    private final ScorecardItemRepository scorecardItemRepository;
    private final CallAudioFileRepository audioFileRepository;
    private final CallTranscriptSegmentRepository transcriptSegmentRepository;
    private final CcRecordingRepository recordingRepository;
    private final CcInteractionRepository interactionRepository;
    private final CcAgentRepository agentRepository;

    @Transactional
    public AppealView createAppeal(Long evaluationId, CcAgent agent, String reason) {
        CallEvaluation evaluation = evaluationRepository.findById(evaluationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Avaliação não encontrada"));

        // Validação anti-IDOR: a avaliação precisa pertencer ao agente autenticado
        CallAudioFile audioFile = audioFileRepository.findById(evaluation.getAudioFileId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Arquivo de áudio não encontrado"));

        CcInteraction interaction = null;
        if (audioFile.getCcRecordingId() != null) {
            Optional<CcRecording> recOpt = recordingRepository.findById(audioFile.getCcRecordingId());
            if (recOpt.isPresent() && recOpt.get().getInteractionId() != null) {
                interaction = interactionRepository.findById(recOpt.get().getInteractionId()).orElse(null);
                if (interaction != null && interaction.getAgent() != null && !interaction.getAgent().getId().equals(agent.getId())) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Você não tem permissão para contestar avaliação de outro agente");
                }
            }
        }

        // Checar se já existe contestação PENDENTE
        Optional<CcEvaluationAppeal> existingPending = appealRepository.findByEvaluationIdAndAgentId(evaluationId, agent.getId());
        if (existingPending.isPresent() && "PENDENTE".equals(existingPending.get().getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe uma contestação pendente para esta avaliação");
        }

        CcEvaluationAppeal appeal = CcEvaluationAppeal.builder()
                .evaluation(evaluation)
                .agent(agent)
                .interaction(interaction)
                .reason(reason)
                .status("PENDENTE")
                .build();

        CcEvaluationAppeal saved = appealRepository.save(appeal);
        log.info("Contestação #{} criada pelo agente #{} para avaliação #{}", saved.getId(), agent.getId(), evaluationId);
        return AppealView.from(saved);
    }

    @Transactional(readOnly = true)
    public List<AppealView> getAppealsForAgent(Long agentId) {
        return appealRepository.findByAgentIdOrderByCreatedAtDesc(agentId).stream()
                .map(AppealView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CoachingPlanView> getCoachingPlansForAgent(Long agentId) {
        return coachingPlanRepository.findByAgentIdOrderByCreatedAtDesc(agentId).stream()
                .map(CoachingPlanView::from)
                .toList();
    }

    @Transactional
    public CoachingPlanView updateCoachingPlanStatusByAgent(Long planId, Long agentId, String status) {
        CcAgentCoachingPlan plan = coachingPlanRepository.findById(planId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plano de coaching não encontrado"));

        if (!plan.getAgent().getId().equals(agentId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso negado a este plano de coaching");
        }

        if (!"CONCLUIDO".equalsIgnoreCase(status) && !"EM_ANDAMENTO".equalsIgnoreCase(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status inválido para atualização pelo agente");
        }

        plan.setStatus(status.toUpperCase());
        if ("CONCLUIDO".equalsIgnoreCase(status)) {
            plan.setCompletedAt(LocalDateTime.now());
        } else {
            plan.setCompletedAt(null);
        }

        return CoachingPlanView.from(coachingPlanRepository.save(plan));
    }

    @Transactional(readOnly = true)
    public List<DesktopEvaluationDetailView> getEvaluationsForAgent(CcAgent agent, LocalDate de, LocalDate ate) {
        LocalDate startDate = de != null ? de : LocalDate.now().minusDays(30);
        LocalDate endDate = ate != null ? ate : LocalDate.now();

        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.atTime(23, 59, 59);

        List<CcInteraction> interactions = interactionRepository.findByAgentIdAndQueuedAtBetween(agent.getId(), start, end);
        if (interactions.isEmpty()) {
            return List.of();
        }

        Map<Long, CcInteraction> interactionByRecId = interactions.stream()
                .map(i -> recordingRepository.findByInteractionId(i.getId()).map(r -> Map.entry(r.getId(), i)).orElse(null))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));

        if (interactionByRecId.isEmpty()) {
            return List.of();
        }

        List<Long> recIds = new ArrayList<>(interactionByRecId.keySet());
        List<CallAudioFile> audioFiles = audioFileRepository.findByCcRecordingIdIn(recIds);
        if (audioFiles.isEmpty()) {
            return List.of();
        }

        List<Long> audioFileIds = audioFiles.stream().map(CallAudioFile::getId).toList();
        List<CallEvaluation> evaluations = evaluationRepository.findByAudioFileIdIn(audioFileIds);

        Map<Long, CallAudioFile> audioMap = audioFiles.stream().collect(Collectors.toMap(CallAudioFile::getId, a -> a));
        Map<Long, String> scorecardNames = scorecardRepository.findAll().stream()
                .collect(Collectors.toMap(QualityScorecard::getId, QualityScorecard::getName, (a, b) -> a));

        List<DesktopEvaluationDetailView> result = new ArrayList<>();
        for (CallEvaluation eval : evaluations) {
            CallAudioFile caf = audioMap.get(eval.getAudioFileId());
            CcInteraction inter = caf != null && caf.getCcRecordingId() != null ? interactionByRecId.get(caf.getCcRecordingId()) : null;

            List<CallEvaluationItem> items = evaluationItemRepository.findByEvaluationIdOrderByIdAsc(eval.getId());
            Map<Long, ScorecardItem> scorecardItemMap = items.isEmpty() ? Map.of()
                    : scorecardItemRepository.findAllById(items.stream().map(CallEvaluationItem::getItemId).toList())
                            .stream().collect(Collectors.toMap(ScorecardItem::getId, s -> s));

            List<DesktopEvaluationDetailView.EvaluationItemDetail> itemDetails = items.stream().map(item -> {
                ScorecardItem si = scorecardItemMap.get(item.getItemId());
                return new DesktopEvaluationDetailView.EvaluationItemDetail(
                        item.getItemId(),
                        si != null ? si.getPergunta() : "Critério",
                        item.getNota(),
                        si != null ? si.getNotaMaxima() : 10,
                        si != null ? si.getPeso() : BigDecimal.ONE,
                        si != null ? si.getIsCritical() : false,
                        item.getJustificativa(),
                        item.getTrechoReferencia()
                );
            }).toList();

            Optional<CcEvaluationAppeal> appealOpt = appealRepository.findByEvaluationIdAndAgentId(eval.getId(), agent.getId());
            AppealView appealView = appealOpt.map(AppealView::from).orElse(null);

            String transcript = null;
            if (caf != null) {
                transcript = transcriptSegmentRepository.findByAudioFileIdOrderByStartMsAsc(caf.getId()).stream()
                        .map(s -> s.getSpeaker() + ": " + s.getText())
                        .collect(Collectors.joining("\n"));
            }

            result.add(new DesktopEvaluationDetailView(
                    eval.getId(),
                    eval.getAudioFileId(),
                    inter != null ? inter.getId() : null,
                    caf != null && caf.getCallStarttime() != null ? caf.getCallStarttime() : (inter != null ? inter.getQueuedAt() : null),
                    inter != null ? inter.getAni() : (caf != null ? caf.getAni() : "—"),
                    inter != null && inter.getQueue() != null ? inter.getQueue().getName() : "—",
                    eval.getNotaTotal(),
                    eval.getIsFailed(),
                    eval.getFailReason(),
                    scorecardNames.getOrDefault(eval.getScorecardId(), "Ficha de Avaliação"),
                    itemDetails,
                    appealView,
                    transcript
            ));
        }

        return result;
    }

    // ─── Métodos de Supervisão ───────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AppealView> listPendingAppeals() {
        return appealRepository.findAllPendingWithDetails("PENDENTE").stream()
                .map(AppealView::from)
                .toList();
    }

    @Transactional
    public AppealView reviewAppeal(Long appealId, String supervisorUsername, ReviewAppealRequest request) {
        CcEvaluationAppeal appeal = appealRepository.findById(appealId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contestação não encontrada"));

        String status = request.status().toUpperCase();
        if (!"APROVADA".equals(status) && !"REJEITADA".equals(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status deve ser APROVADA ou REJEITADA");
        }

        appeal.setStatus(status);
        appeal.setSupervisorNotes(request.supervisorNotes());
        appeal.setReviewedBy(supervisorUsername);
        appeal.setReviewedAt(LocalDateTime.now());

        if ("APROVADA".equals(status) && request.newScore() != null) {
            CallEvaluation eval = appeal.getEvaluation();
            eval.setNotaTotal(request.newScore());
            eval.setIsFailed(false);
            eval.setFailReason(null);
            evaluationRepository.save(eval);
            log.info("Nota da avaliação #{} atualizada para {} após aprovação de recurso", eval.getId(), request.newScore());
        }

        return AppealView.from(appealRepository.save(appeal));
    }

    @Transactional
    public CoachingPlanView createCoachingPlan(String supervisorUsername, CreateCoachingPlanRequest request) {
        CcAgent agent = agentRepository.findById(request.agentId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agente não encontrado"));

        ScorecardItem item = request.scorecardItemId() != null
                ? scorecardItemRepository.findById(request.scorecardItemId()).orElse(null)
                : null;

        CallEvaluation eval = request.evaluationId() != null
                ? evaluationRepository.findById(request.evaluationId()).orElse(null)
                : null;

        CcAgentCoachingPlan plan = CcAgentCoachingPlan.builder()
                .agent(agent)
                .scorecardItem(item)
                .evaluation(eval)
                .title(request.title())
                .description(request.description())
                .actionItems(request.actionItems())
                .targetScore(request.targetScore())
                .deadline(request.deadline() != null ? request.deadline() : LocalDate.now().plusDays(15))
                .status("EM_ANDAMENTO")
                .createdBy(supervisorUsername != null ? supervisorUsername : "SUPERVISOR")
                .build();

        return CoachingPlanView.from(coachingPlanRepository.save(plan));
    }
}
