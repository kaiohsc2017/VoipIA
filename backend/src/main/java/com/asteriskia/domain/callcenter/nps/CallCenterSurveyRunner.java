package com.asteriskia.domain.callcenter.nps;

import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.flow.engine.ChannelDriver;
import com.asteriskia.domain.callcenter.interaction.CcInteraction;
import com.asteriskia.domain.callcenter.interaction.CcInteractionRepository;
import com.asteriskia.telegram.TelegramBotService;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * CallCenterSurveyRunner — executa as perguntas de uma {@link CcSurvey} contra um
 * {@link ChannelDriver} (Fase 21). Compartilhado pelos dois pontos de entrada de uma pesquisa:
 * {@code SurveyNodeHandler} (nó {@code pesquisa_satisfacao} dentro de um fluxo comum) e
 * {@code CallCenterNpsExecutionService} (disparo direto pós-fila via {@code Queue(F(...))}) —
 * nenhum dos dois reimplementa a lógica de pergunta/resposta/nota.
 *
 * <p><b>Nunca bloqueante</b> (§4.2 do plano): qualquer falha aqui é capturada pelo chamador, que
 * sempre encerra a chamada normalmente — uma pesquisa quebrada nunca prende o cliente na linha.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallCenterSurveyRunner {

    private static final Duration DTMF_PROMPT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RECORD_MAX_DURATION = Duration.ofSeconds(60);

    // Mesma property já usada por CallCenterRecordingService (Fase 20) — a gravação de voz da
    // NPS é a mesma classe de dado (áudio de cliente) e segue a mesma raiz de mídia do projeto.
    @Value("${app.callcenter.recording-path:/opt/VoipIA/media/gravacao}")
    private String recordingBasePath;

    private final CcSurveyQuestionRepository questionRepository;
    private final CcSurveyResponseRepository responseRepository;
    private final CcInteractionRepository interactionRepository;
    private final TelegramBotService telegramBotService;

    /** Roda a pesquisa inteira. {@code channelId} é usado para achar a {@link CcInteraction}
     * correspondente (best-effort — nem todo disparo de pesquisa tem uma interação de fila por
     * trás, ex.: nó dentro de um fluxo autônomo); sem interação encontrada, as respostas ainda
     * são gravadas, só não há nota desnormalizada nem alerta.
     *
     * <p><b>Deliberadamente sem {@code @Transactional} aqui</b> (achado de revisão): o loop
     * bloqueia em {@code driver.promptChoice}/{@code driver.recordResponse} esperando o cliente
     * na chamada (até ~65s por pergunta) — uma transação envolvendo isso manteria uma conexão do
     * pool presa por minutos sem fazer nenhum trabalho de banco na maior parte do tempo. Cada
     * {@code responseRepository.save}/{@code interactionRepository.save} já é transacional por
     * conta própria (Spring Data), e {@link #recomputeInteractionNpsScore} tem sua própria
     * transação curta — não há cenário aqui que exija atomicidade entre as perguntas. */
    public void run(CcSurvey survey, ChannelDriver driver, String channelId, CcQueue queueForAlert) {
        var questions = questionRepository.findBySurveyIdOrderByOrderIndexAsc(survey.getId());
        if (questions.isEmpty()) {
            log.warn("Pesquisa {} sem nenhuma pergunta configurada — nada a executar.", survey.getId());
            return;
        }
        CcInteraction interaction = channelId == null ? null : interactionRepository.findByChannelUniqueId(channelId).orElse(null);

        for (var question : questions) {
            runQuestion(survey, question, driver, interaction);
        }

        if (interaction != null) {
            recomputeInteractionNpsScore(interaction, queueForAlert);
        }
    }

    private void runQuestion(CcSurvey survey, CcSurveyQuestion question, ChannelDriver driver, CcInteraction interaction) {
        driver.playMessage(question.getAudioPath(), question.getText());
        switch (survey.getMode()) {
            case DTMF_SIMPLES, DTMF_MULTI -> runDtmfNote(survey, question, driver, interaction);
            case DTMF_COMENTARIO -> {
                runDtmfNote(survey, question, driver, interaction);
                runRecordedComment(question, driver, interaction, /* autoTranscribe= */ false);
            }
            case FALADA_IA -> runRecordedComment(question, driver, interaction, /* autoTranscribe= */ true);
        }
    }

    private void runDtmfNote(CcSurvey survey, CcSurveyQuestion question, ChannelDriver driver, CcInteraction interaction) {
        var choices = validChoices(survey.getScaleMax());
        var result = driver.promptChoice(choices, DTMF_PROMPT_TIMEOUT);
        var response = CcSurveyResponse.builder().interaction(interaction).question(question);
        if (result.outcome() == ChannelDriver.PromptResult.Outcome.CHOSEN) {
            response.value(digitToScore(result.choice(), survey.getScaleMax()));
        } else {
            response.skippedReason(result.outcome() == ChannelDriver.PromptResult.Outcome.TIMEOUT ? "TIMEOUT" : "HUNG_UP");
        }
        responseRepository.save(response.build());
    }

    /** {@code autoTranscribe=true} (FALADA_IA) marca a resposta para o scheduler assíncrono
     * pegar; {@code false} (DTMF_COMENTARIO) só guarda o áudio — a transcrição é sob demanda
     * (D21), nunca automática, então esta resposta nunca aparece na fila do scheduler. */
    private void runRecordedComment(
            CcSurveyQuestion question, ChannelDriver driver, CcInteraction interaction, boolean autoTranscribe) {
        var result = driver.recordResponse(RECORD_MAX_DURATION);
        var response = CcSurveyResponse.builder().interaction(interaction).question(question);
        if (result.outcome() == ChannelDriver.RecordResult.Outcome.RECORDED) {
            response.audioPath(relocateToMedia(result.audioPath()));
        } else {
            response.skippedReason("HUNG_UP");
        }
        responseRepository.save(response.build());
        if (!autoTranscribe) {
            log.debug(
                    "Comentário de NPS gravado sem transcrição automática (D21) — question={} interaction={}",
                    question.getId(), interaction == null ? null : interaction.getId());
        }
    }

    /** Move o arquivo do spool transiente do ARI ({@code /var/spool/asterisk/recording/}, achado
     * de segurança MEDIUM desta fase — nunca era movido, ficava fora da política de retenção do
     * resto do projeto) para {@code media/gravacao/nps/}, a mesma raiz de mídia permanente de
     * qualquer outro áudio de cliente do Call Center (Fase 20). Falha na cópia não derruba a
     * pesquisa — mantém o caminho original antes que perder o arquivo por completo. */
    private String relocateToMedia(String spoolPath) {
        try {
            var source = Path.of(spoolPath);
            var destDir = Path.of(recordingBasePath, "nps");
            Files.createDirectories(destDir);
            var dest = destDir.resolve(UUID.randomUUID() + ".wav");
            Files.move(source, dest, StandardCopyOption.REPLACE_EXISTING);
            return dest.toString();
        } catch (IOException e) {
            log.warn("Falha ao mover gravação de NPS do spool ARI para media/gravacao/nps — mantendo caminho original ({}): {}",
                    spoolPath, e.getMessage());
            return spoolPath;
        }
    }

    /** Recalcula {@code cc_interactions.nps_score} como a média das respostas com nota (ignora
     * puladas e FALADA_IA ainda não classificadas) e dispara o alerta de NPS baixo, se
     * configurado na fila. Chamado tanto ao final da execução em voz quanto pelo scheduler
     * assíncrono, sempre que uma resposta FALADA_IA ganha nota via classificação por IA. */
    @Transactional
    public void recomputeInteractionNpsScore(CcInteraction interaction, CcQueue queueForAlert) {
        var responses = responseRepository.findByInteractionId(interaction.getId());
        var scores = responses.stream().map(CcSurveyResponse::getValue).filter(java.util.Objects::nonNull).toList();
        BigDecimal npsScore =
                scores.isEmpty()
                        ? null
                        : BigDecimal.valueOf(scores.stream().mapToInt(Integer::intValue).average().orElse(0))
                                .setScale(1, RoundingMode.HALF_UP);
        interaction.setNpsScore(npsScore);
        interactionRepository.save(interaction);
        if (npsScore != null && queueForAlert != null) {
            checkLowScoreAlert(queueForAlert, npsScore);
        }
    }

    private void checkLowScoreAlert(CcQueue queue, BigDecimal npsScore) {
        if (!Boolean.TRUE.equals(queue.getNpsAlertEnabled()) || queue.getNpsAlertThreshold() == null) {
            return;
        }
        if (npsScore.compareTo(BigDecimal.valueOf(queue.getNpsAlertThreshold())) > 0) {
            return;
        }
        telegramBotService.sendMessage(
                String.format(
                        """
                        ⚠️ *NPS baixo — fila %s*
                        Nota: %s (limite configurado: %d)
                        Verifique a chamada no histórico do Call Center para possível resgate ainda hoje.""",
                        queue.getDisplayName(), npsScore.toPlainString(), queue.getNpsAlertThreshold()));
        log.info("Alerta de NPS baixo disparado: fila={} nota={}", queue.getName(), npsScore);
    }

    private List<String> validChoices(int scaleMax) {
        var choices = new ArrayList<String>();
        for (int i = 0; i <= Math.min(scaleMax, 9); i++) {
            choices.add(String.valueOf(i));
        }
        if (scaleMax == 10) {
            choices.add("*");
        }
        return choices;
    }

    private int digitToScore(String digit, int scaleMax) {
        return "*".equals(digit) ? scaleMax : Integer.parseInt(digit);
    }
}
