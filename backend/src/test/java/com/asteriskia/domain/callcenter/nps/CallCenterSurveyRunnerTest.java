package com.asteriskia.domain.callcenter.nps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.flow.engine.ChannelDriver;
import com.asteriskia.domain.callcenter.interaction.CcInteraction;
import com.asteriskia.domain.callcenter.interaction.CcInteractionRepository;
import com.asteriskia.telegram.TelegramBotService;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * CallCenterSurveyRunnerTest — execução das perguntas de uma pesquisa (Fase 21): coleta DTMF,
 * gravação de comentário/resposta falada, cálculo da nota desnormalizada e alerta de NPS baixo.
 */
@ExtendWith(MockitoExtension.class)
class CallCenterSurveyRunnerTest {

    @Mock private CcSurveyQuestionRepository questionRepository;
    @Mock private CcSurveyResponseRepository responseRepository;
    @Mock private CcInteractionRepository interactionRepository;
    @Mock private TelegramBotService telegramBotService;
    @Mock private ChannelDriver driver;

    @TempDir private Path tempDir;

    private CallCenterSurveyRunner newRunner() {
        var runner = new CallCenterSurveyRunner(questionRepository, responseRepository, interactionRepository, telegramBotService);
        setRecordingBasePath(runner, tempDir.toString());
        return runner;
    }

    private static void setRecordingBasePath(CallCenterSurveyRunner runner, String path) {
        try {
            Field field = CallCenterSurveyRunner.class.getDeclaredField("recordingBasePath");
            field.setAccessible(true);
            field.set(runner, path);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private CcSurveyQuestion question(long id, CcSurvey survey, int order) {
        return CcSurveyQuestion.builder().id(id).survey(survey).orderIndex(order).text("Pergunta " + order).build();
    }

    @Test
    @DisplayName("DTMF_SIMPLES: dígito '7' vira nota 7 e é desnormalizado em cc_interactions.nps_score")
    void run_dtmfSimples_persistsScoreOnInteraction() {
        var survey = CcSurvey.builder().id(1L).mode(SurveyMode.DTMF_SIMPLES).scaleMax(10).build();
        var q = question(10L, survey, 1);
        var interaction = CcInteraction.builder().id(5L).build();
        when(questionRepository.findBySurveyIdOrderByOrderIndexAsc(1L)).thenReturn(List.of(q));
        when(interactionRepository.findByChannelUniqueId("chan-1")).thenReturn(Optional.of(interaction));
        when(driver.promptChoice(any(), any())).thenReturn(ChannelDriver.PromptResult.chosen("7"));
        when(responseRepository.findByInteractionId(5L))
                .thenReturn(List.of(CcSurveyResponse.builder().value(7).build()));

        newRunner().run(survey, driver, "chan-1", null);

        var responseCaptor = ArgumentCaptor.forClass(CcSurveyResponse.class);
        verify(responseRepository).save(responseCaptor.capture());
        assertThat(responseCaptor.getValue().getValue()).isEqualTo(7);

        var interactionCaptor = ArgumentCaptor.forClass(CcInteraction.class);
        verify(interactionRepository).save(interactionCaptor.capture());
        assertThat(interactionCaptor.getValue().getNpsScore()).isEqualByComparingTo("7.0");
    }

    @Test
    @DisplayName("DTMF_SIMPLES: '*' na escala 0-10 vale nota máxima (10)")
    void run_dtmfSimples_starDigitMeansMaxScore() {
        var survey = CcSurvey.builder().id(1L).mode(SurveyMode.DTMF_SIMPLES).scaleMax(10).build();
        var q = question(10L, survey, 1);
        when(questionRepository.findBySurveyIdOrderByOrderIndexAsc(1L)).thenReturn(List.of(q));
        when(driver.promptChoice(any(), any())).thenReturn(ChannelDriver.PromptResult.chosen("*"));

        newRunner().run(survey, driver, null, null);

        var responseCaptor = ArgumentCaptor.forClass(CcSurveyResponse.class);
        verify(responseRepository).save(responseCaptor.capture());
        assertThat(responseCaptor.getValue().getValue()).isEqualTo(10);
    }

    @Test
    @DisplayName("timeout no DTMF grava a resposta como pulada (skippedReason), sem nota")
    void run_dtmfTimeout_skipsWithReason() {
        var survey = CcSurvey.builder().id(1L).mode(SurveyMode.DTMF_SIMPLES).scaleMax(10).build();
        var q = question(10L, survey, 1);
        when(questionRepository.findBySurveyIdOrderByOrderIndexAsc(1L)).thenReturn(List.of(q));
        when(driver.promptChoice(any(), any())).thenReturn(ChannelDriver.PromptResult.timeout());

        newRunner().run(survey, driver, null, null);

        var responseCaptor = ArgumentCaptor.forClass(CcSurveyResponse.class);
        verify(responseRepository).save(responseCaptor.capture());
        assertThat(responseCaptor.getValue().getValue()).isNull();
        assertThat(responseCaptor.getValue().getSkippedReason()).isEqualTo("TIMEOUT");
    }

    @Test
    @DisplayName("FALADA_IA move o áudio do spool ARI para media/gravacao/nps, sem nota nem transcript")
    void run_faladaIa_recordsAudioWithoutScore() throws Exception {
        var survey = CcSurvey.builder().id(1L).mode(SurveyMode.FALADA_IA).scaleMax(10).build();
        var q = question(10L, survey, 1);
        var spoolFile = tempDir.resolve("spool-audio.wav");
        Files.writeString(spoolFile, "fake wav bytes");
        when(questionRepository.findBySurveyIdOrderByOrderIndexAsc(1L)).thenReturn(List.of(q));
        when(driver.recordResponse(any())).thenReturn(ChannelDriver.RecordResult.recorded(spoolFile.toString()));

        newRunner().run(survey, driver, null, null);

        var responseCaptor = ArgumentCaptor.forClass(CcSurveyResponse.class);
        verify(responseRepository).save(responseCaptor.capture());
        var movedPath = Path.of(responseCaptor.getValue().getAudioPath());
        assertThat(movedPath).exists();
        assertThat(movedPath.getParent()).isEqualTo(tempDir.resolve("nps"));
        assertThat(Files.exists(spoolFile)).isFalse();
        assertThat(responseCaptor.getValue().getValue()).isNull();
        assertThat(responseCaptor.getValue().getTranscript()).isNull();
    }

    @Test
    @DisplayName("DTMF_COMENTARIO: nota por dígito + grava o comentário, sem chamar IA (D21)")
    void run_dtmfComentario_notaAndRecordedCommentNeverAutoTranscribed() {
        var survey = CcSurvey.builder().id(1L).mode(SurveyMode.DTMF_COMENTARIO).scaleMax(10).build();
        var q = question(10L, survey, 1);
        when(questionRepository.findBySurveyIdOrderByOrderIndexAsc(1L)).thenReturn(List.of(q));
        when(driver.promptChoice(any(), any())).thenReturn(ChannelDriver.PromptResult.chosen("3"));
        when(driver.recordResponse(any())).thenReturn(ChannelDriver.RecordResult.recorded("/tmp/comentario.wav"));

        newRunner().run(survey, driver, null, null);

        verify(responseRepository, org.mockito.Mockito.times(2)).save(any());
    }

    @Test
    @DisplayName("recomputeInteractionNpsScore dispara alerta Telegram quando nota fica no limite configurado ou abaixo")
    void recompute_lowScore_firesAlert() {
        var interaction = CcInteraction.builder().id(5L).build();
        var queue = CcQueue.builder().name("5001").displayName("Fila Teste")
                .npsAlertEnabled(true).npsAlertThreshold(3).build();
        when(responseRepository.findByInteractionId(5L))
                .thenReturn(List.of(CcSurveyResponse.builder().value(2).build()));

        newRunner().recomputeInteractionNpsScore(interaction, queue);

        assertThat(interaction.getNpsScore()).isEqualByComparingTo("2.0");
        verify(telegramBotService).sendMessage(any());
    }

    @Test
    @DisplayName("recomputeInteractionNpsScore não dispara alerta quando a nota está acima do limite")
    void recompute_highScore_doesNotFireAlert() {
        var interaction = CcInteraction.builder().id(5L).build();
        var queue = CcQueue.builder().name("5001").displayName("Fila Teste")
                .npsAlertEnabled(true).npsAlertThreshold(3).build();
        when(responseRepository.findByInteractionId(5L))
                .thenReturn(List.of(CcSurveyResponse.builder().value(9).build()));

        newRunner().recomputeInteractionNpsScore(interaction, queue);

        verify(telegramBotService, never()).sendMessage(any());
    }

    @Test
    @DisplayName("recomputeInteractionNpsScore não dispara alerta se a fila não tem alerta habilitado")
    void recompute_alertDisabled_doesNotFireAlert() {
        var interaction = CcInteraction.builder().id(5L).build();
        var queue = CcQueue.builder().name("5001").displayName("Fila Teste")
                .npsAlertEnabled(false).npsAlertThreshold(3).build();
        when(responseRepository.findByInteractionId(5L))
                .thenReturn(List.of(CcSurveyResponse.builder().value(1).build()));

        newRunner().recomputeInteractionNpsScore(interaction, queue);

        verify(telegramBotService, never()).sendMessage(any());
    }

    @Test
    @DisplayName("recomputeInteractionNpsScore ignora respostas FALADA_IA ainda sem nota (pendentes)")
    void recompute_ignoresNullValues() {
        var interaction = CcInteraction.builder().id(5L).build();
        when(responseRepository.findByInteractionId(5L))
                .thenReturn(List.of(
                        CcSurveyResponse.builder().value(8).build(),
                        CcSurveyResponse.builder().value(null).build()));

        newRunner().recomputeInteractionNpsScore(interaction, null);

        assertThat(interaction.getNpsScore()).isEqualByComparingTo("8.0");
    }

    @Test
    @DisplayName("run sem interação correspondente (channelId null) ainda grava as respostas, sem nota nem alerta")
    void run_withoutInteraction_stillRecordsResponses() {
        var survey = CcSurvey.builder().id(1L).mode(SurveyMode.DTMF_SIMPLES).scaleMax(10).build();
        var q = question(10L, survey, 1);
        when(questionRepository.findBySurveyIdOrderByOrderIndexAsc(1L)).thenReturn(List.of(q));
        when(driver.promptChoice(any(), any())).thenReturn(ChannelDriver.PromptResult.chosen("5"));

        newRunner().run(survey, driver, null, null);

        verify(responseRepository).save(any());
        verify(interactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("pesquisa sem nenhuma pergunta não executa nada")
    void run_noQuestions_doesNothing() {
        var survey = CcSurvey.builder().id(1L).mode(SurveyMode.DTMF_SIMPLES).scaleMax(10).build();
        when(questionRepository.findBySurveyIdOrderByOrderIndexAsc(1L)).thenReturn(List.of());

        newRunner().run(survey, driver, "chan-1", null);

        verify(driver, never()).promptChoice(any(), any());
        verify(responseRepository, never()).save(any());
    }
}
