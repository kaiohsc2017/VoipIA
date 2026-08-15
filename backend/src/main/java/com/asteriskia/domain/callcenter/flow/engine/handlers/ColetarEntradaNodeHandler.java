package com.asteriskia.domain.callcenter.flow.engine.handlers;

import com.asteriskia.domain.callcenter.flow.engine.ChannelDriver;
import com.asteriskia.domain.callcenter.flow.engine.FlowExecutionContext;
import com.asteriskia.domain.callcenter.flow.engine.FlowGraph;
import com.asteriskia.domain.callcenter.flow.engine.NodeHandler;
import com.asteriskia.domain.callcenter.identity.CallCenterIdentityResolver;
import com.asteriskia.domain.callcenter.identity.IdentitySource;
import com.asteriskia.domain.callcenter.identity.ResolvedIdentity;
import com.asteriskia.domain.callcenter.interaction.CcInteractionRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ColetarEntradaNodeHandler — nó "coletar_entrada" (Fase 14, exclusivo do canal voz). Grava a
 * resposta falada do cliente ({@code driver.recordResponse}, mesmo mecanismo já usado por
 * {@code SurveyNodeHandler}/modo FALADA_IA) e transcreve via {@link CallCenterIdentityResolver}.
 *
 * <p>Propriedade {@code sensivel}: como {@code FlowExecutionTraceService} hoje nunca persiste
 * {@code detail} de um passo comum (só {@code nodeId}/{@code nodeType}/{@code takenEdge}), o
 * valor coletado nunca chega ao traço de execução independente desta flag — ela é honrada aqui
 * suprimindo o valor também dos LOGS deste handler (nunca {@code log.info(transcript)}), para que
 * ligar {@code sensivel} continue tendo efeito real caso o traço passe a registrar detail no
 * futuro.
 *
 * <p>Propriedade {@code identificarContato}: quando ligada, tenta resolver a identidade do
 * contato contra o AD (busca aproximada por nome falado, D7) — SEMPRE com confirmação falada
 * antes de persistir ("Confirma [Nome]? Diga sim ou não"). Sem confirmação positiva clara, o
 * contato permanece {@code UNRESOLVED} (comportamento fail-open: o fluxo sempre continua, nunca
 * prende a chamada por causa de identificação).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ColetarEntradaNodeHandler implements NodeHandler {

    private static final Duration COLLECT_DURATION = Duration.ofSeconds(15);
    private static final Duration CONFIRM_DURATION = Duration.ofSeconds(8);

    private final CallCenterIdentityResolver identityResolver;
    private final CcInteractionRepository interactionRepository;

    @Override
    public String nodeType() {
        return "coletar_entrada";
    }

    @Override
    public Optional<FlowGraph.Edge> handle(FlowGraph graph, FlowGraph.Node node, FlowExecutionContext context) {
        var driver = context.driver();
        var sensivel = "true".equalsIgnoreCase(node.data().property("sensivel"));
        var identificarContato = "true".equalsIgnoreCase(node.data().property("identificarContato"));

        var recordResult = driver.recordResponse(COLLECT_DURATION);
        if (recordResult.outcome() == ChannelDriver.RecordResult.Outcome.HUNG_UP) {
            return Optional.empty();
        }

        String transcript = transcribeQuietly(recordResult.audioPath(), sensivel);

        var variavel = node.data().property("variavel");
        if (variavel != null && !variavel.isBlank() && transcript != null && !transcript.isBlank()) {
            context.setVariable(variavel, transcript);
            driver.setVariable(variavel, transcript);
        }

        if (identificarContato && transcript != null && !transcript.isBlank()) {
            resolveAndConfirm(driver, context.channelId(), transcript);
        }

        var edge = graph.outgoingEdges(node.id()).stream().findFirst();
        if (edge.isEmpty()) {
            driver.end();
        }
        return edge;
    }

    private void resolveAndConfirm(ChannelDriver driver, String channelId, String spokenName) {
        Optional<ResolvedIdentity> candidateOpt = identityResolver.findFuzzyCandidateByName(spokenName);
        if (candidateOpt.isEmpty()) {
            identityResolver.logResolution("voice", "unresolved", BigDecimal.ZERO);
            log.info("Identificação de contato por voz: nenhum candidato acima do limiar de similaridade.");
            return;
        }
        var candidate = candidateOpt.get();
        driver.playMessage(
                null, "Confirma " + candidate.adUser().getDisplayName() + "? Diga sim ou não.");
        var confirmRecording = driver.recordResponse(CONFIRM_DURATION);
        if (confirmRecording.outcome() == ChannelDriver.RecordResult.Outcome.HUNG_UP) {
            identityResolver.logResolution("voice", "unresolved", BigDecimal.ZERO);
            return;
        }
        var confirmResult = identityResolver.transcribe(readAudioOrEmpty(confirmRecording.audioPath()));
        if (identityResolver.isSpokenConfirmationPositive(confirmResult.transcript())) {
            persistIdentity(channelId, candidate.adUser().getSamAccountName());
            identityResolver.logResolution("voice", "resolved", confirmResult.costUsd());
            log.info("Contato identificado por voz e confirmado (canal={}).", channelId);
        } else {
            identityResolver.logResolution("voice", "rejected", confirmResult.costUsd());
            log.info("Confirmação de identidade negada pelo cliente (canal={}).", channelId);
        }
    }

    // Sem @Transactional aqui de propósito: é autoinvocação (chamada de dentro de handle/
    // resolveAndConfirm do mesmo bean), então a anotação seria inerte — não passaria pelo proxy
    // do Spring (mesma disciplina já documentada em CallCenterNpsTranscriptionScheduler).
    // interactionRepository.save(...) já é transacional por conta própria (bean do Spring Data).
    private void persistIdentity(String channelUniqueId, String samAccountName) {
        interactionRepository
                .findByChannelUniqueId(channelUniqueId)
                .ifPresent(
                        interaction -> {
                            interaction.setResolvedAdSam(samAccountName);
                            interaction.setIdentitySource(IdentitySource.URA_INPUT.name());
                            interactionRepository.save(interaction);
                        });
    }

    private String transcribeQuietly(String audioPath, boolean sensivel) {
        try {
            byte[] audio = Files.readAllBytes(Path.of(audioPath));
            var result = identityResolver.transcribe(audio);
            if (!sensivel) {
                log.debug("coletar_entrada transcrito com sucesso (path={}).", audioPath);
            }
            return result.transcript();
        } catch (IOException e) {
            log.warn("Áudio de coletar_entrada não encontrado/legível (path={}).", audioPath);
            return null;
        }
    }

    private byte[] readAudioOrEmpty(String audioPath) {
        if (audioPath == null) {
            return new byte[0];
        }
        try {
            return Files.readAllBytes(Path.of(audioPath));
        } catch (IOException e) {
            return new byte[0];
        }
    }
}
