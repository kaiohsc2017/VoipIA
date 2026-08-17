package com.asteriskia.domain.callcenter.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class CallCenterQualityCoachingServiceTest {

    @Mock private CcEvaluationAppealRepository appealRepository;
    @Mock private CcAgentCoachingPlanRepository coachingPlanRepository;
    @Mock private CallEvaluationRepository evaluationRepository;
    @Mock private CallEvaluationItemRepository evaluationItemRepository;
    @Mock private QualityScorecardRepository scorecardRepository;
    @Mock private ScorecardItemRepository scorecardItemRepository;
    @Mock private CallAudioFileRepository audioFileRepository;
    @Mock private CallTranscriptSegmentRepository transcriptSegmentRepository;
    @Mock private CcRecordingRepository recordingRepository;
    @Mock private CcInteractionRepository interactionRepository;
    @Mock private CcAgentRepository agentRepository;

    private CallCenterQualityCoachingService service;
    private CcAgent agent;
    private CallEvaluation evaluation;
    private CallAudioFile audioFile;

    @BeforeEach
    void setUp() {
        service = new CallCenterQualityCoachingService(
                appealRepository,
                coachingPlanRepository,
                evaluationRepository,
                evaluationItemRepository,
                scorecardRepository,
                scorecardItemRepository,
                audioFileRepository,
                transcriptSegmentRepository,
                recordingRepository,
                interactionRepository,
                agentRepository);

        agent = CcAgent.builder().id(10L).name("Agente Teste").build();
        evaluation = CallEvaluation.builder().id(100L).audioFileId(500L).scorecardId(1L).notaTotal(BigDecimal.valueOf(65)).isFailed(false).build();
        audioFile = CallAudioFile.builder().id(500L).ccRecordingId(800L).build();
    }

    @Test
    @DisplayName("createAppeal() cria contestação com sucesso para o agente dono da chamada")
    void createAppeal_success() {
        when(evaluationRepository.findById(100L)).thenReturn(Optional.of(evaluation));
        when(audioFileRepository.findById(500L)).thenReturn(Optional.of(audioFile));

        CcInteraction interaction = CcInteraction.builder().id(900L).agent(agent).build();
        CcRecording recording = CcRecording.builder().id(800L).interactionId(900L).build();
        when(recordingRepository.findById(800L)).thenReturn(Optional.of(recording));
        when(interactionRepository.findById(900L)).thenReturn(Optional.of(interaction));
        when(appealRepository.findByEvaluationIdAndAgentId(100L, 10L)).thenReturn(Optional.empty());

        when(appealRepository.save(any())).thenAnswer(invocation -> {
            CcEvaluationAppeal a = invocation.getArgument(0);
            a.setId(1L);
            return a;
        });

        AppealView view = service.createAppeal(100L, agent, "Discordo da nota no item de saudação.");

        assertThat(view.id()).isEqualTo(1L);
        assertThat(view.status()).isEqualTo("PENDENTE");
        assertThat(view.reason()).contains("Discordo da nota");
    }

    @Test
    @DisplayName("createAppeal() rejeita com 409 quando já existe contestação PENDENTE")
    void createAppeal_rejectsIfAlreadyPending() {
        when(evaluationRepository.findById(100L)).thenReturn(Optional.of(evaluation));
        when(audioFileRepository.findById(500L)).thenReturn(Optional.of(audioFile));
        when(appealRepository.findByEvaluationIdAndAgentId(100L, 10L)).thenReturn(Optional.of(
                CcEvaluationAppeal.builder().id(1L).status("PENDENTE").build()
        ));

        assertThatThrownBy(() -> service.createAppeal(100L, agent, "Nova tentativa"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Já existe uma contestação pendente");
    }

    @Test
    @DisplayName("reviewAppeal() atualiza status e reajusta nota quando aprovada")
    void reviewAppeal_updatesStatusAndScoreWhenApproved() {
        CcEvaluationAppeal appeal = CcEvaluationAppeal.builder()
                .id(1L)
                .evaluation(evaluation)
                .agent(agent)
                .status("PENDENTE")
                .build();

        when(appealRepository.findById(1L)).thenReturn(Optional.of(appeal));
        when(appealRepository.save(any())).thenReturn(appeal);

        ReviewAppealRequest request = new ReviewAppealRequest("APROVADA", "Argumento procedente", BigDecimal.valueOf(85));
        AppealView result = service.reviewAppeal(1L, "supervisor1", request);

        assertThat(result.status()).isEqualTo("APROVADA");
        assertThat(result.supervisorNotes()).isEqualTo("Argumento procedente");
        assertThat(evaluation.getNotaTotal()).isEqualTo(BigDecimal.valueOf(85));
    }

    @Test
    @DisplayName("createCoachingPlan() cria plano de ação com deadline configurado")
    void createCoachingPlan_createsSuccessfully() {
        when(agentRepository.findById(10L)).thenReturn(Optional.of(agent));
        when(coachingPlanRepository.save(any())).thenAnswer(inv -> {
            CcAgentCoachingPlan p = inv.getArgument(0);
            p.setId(5L);
            return p;
        });

        CreateCoachingPlanRequest req = new CreateCoachingPlanRequest(
                10L, null, null, "Melhorar Saudação", "Focar em identificação no início da chamada",
                "[\"Treinar script\", \"Gravar áudio teste\"]", BigDecimal.valueOf(90), LocalDate.now().plusDays(10));

        CoachingPlanView plan = service.createCoachingPlan("supervisor_qa", req);

        assertThat(plan.id()).isEqualTo(5L);
        assertThat(plan.title()).isEqualTo("Melhorar Saudação");
        assertThat(plan.status()).isEqualTo("EM_ANDAMENTO");
        assertThat(plan.createdBy()).isEqualTo("supervisor_qa");
    }
}
