package com.asteriskia.domain.callcenter.flow.engine.handlers;

import com.asteriskia.domain.ai.AiProviderService;
import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.flow.engine.ChannelDriver;
import com.asteriskia.domain.callcenter.flow.engine.FlowExecutionContext;
import com.asteriskia.domain.callcenter.flow.engine.FlowGraph;
import com.asteriskia.domain.callcenter.flow.engine.NodeHandler;
import com.asteriskia.domain.callcenter.ia.CallCenterIaAgentConversationService;
import com.asteriskia.domain.callcenter.ia.CallCenterIaAgentConversationService.ConversationResult;
import com.asteriskia.domain.callcenter.ia.CallCenterIaAgentConversationService.HistoryTurn;
import com.asteriskia.domain.callcenter.ia.CcIaAgent;
import com.asteriskia.domain.callcenter.ia.CcIaAgentRepository;
import com.asteriskia.domain.callcenter.ia.CcIaAgentTurn;
import com.asteriskia.domain.callcenter.ia.CcIaAgentTurnRepository;
import com.asteriskia.domain.callcenter.identity.CallCenterIdentityResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * AgenteIaNodeHandler — nó "agente_ia" (Fase B do plano-mãe do Call Center), canal {@code both}.
 * Executa um laço limitado de pergunta→resposta usando a persona cadastrada em {@link CcIaAgent}
 * (Fase A) — sem RAG nesta fase (ver {@link CallCenterIaAgentConversationService}), conversa livre
 * orientada pelo {@code systemPrompt}.
 *
 * <p>Escala para {@code fallbackQueue} do agente (nunca prende o cliente sem uma saída) quando: a
 * configuração está ausente/inativa, não há API key do Gemini, o cliente para de responder mas o
 * canal ainda está de pé (timeout), o custo acumulado ultrapassa {@code maxCostUsd}, a chamada ao
 * Gemini falha, ou {@code maxTurns} é atingido sem o modelo sinalizar conclusão natural (ver
 * {@link CallCenterIaAgentConversationService#converse}). Sem {@code fallbackQueue} configurada,
 * encerra a chamada/conversa (fail-safe). Se o cliente desliga/fecha o canal (não timeout — o
 * canal em si caiu), nunca escala nem chama {@code driver.end()} — não há mais ninguém do outro
 * lado (mesmo padrão de {@code ColetarTextoNodeHandler}). Quando o modelo sinaliza conclusão
 * natural, segue a primeira aresta de saída do nó (se houver) — mesmo padrão de
 * {@code ConsultarBaseNodeHandler} —, senão encerra normalmente (sem escalar).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgenteIaNodeHandler implements NodeHandler {

    private static final Duration VOICE_RECORD_DURATION = Duration.ofSeconds(15);
    private static final Duration CHAT_COLLECT_TIMEOUT = Duration.ofSeconds(60);
    private static final String CHAT_CHANNEL_ID_PREFIX = "chat-session-";

    private final CcIaAgentRepository agentRepository;
    private final CcIaAgentTurnRepository turnRepository;
    private final CallCenterIaAgentConversationService conversationService;
    private final CallCenterIdentityResolver identityResolver;
    private final AiProviderService aiProviderService;

    @Override
    public String nodeType() {
        return "agente_ia";
    }

    @Override
    public Optional<FlowGraph.Edge> handle(FlowGraph graph, FlowGraph.Node node, FlowExecutionContext context) {
        var driver = context.driver();
        var agent = loadAgent(node.data().property("configuracaoIaId"));
        if (agent.isEmpty()) {
            log.warn("Nó agente_ia sem configuração válida/ativa — encerrando (sem fallback conhecido).");
            driver.end();
            return Optional.empty();
        }

        var apiKey = aiProviderService.getRawKey("gemini");
        if (apiKey.isBlank()) {
            log.warn("Sem API key do Gemini configurada — agente_ia escala direto para o fallback.");
            return escalate(agent.get(), driver);
        }

        var isChat = context.channelId() != null && context.channelId().startsWith(CHAT_CHANNEL_ID_PREFIX);
        if (agent.get().getGreeting() != null && !agent.get().getGreeting().isBlank()) {
            driver.playMessage(null, agent.get().getGreeting());
        }

        List<HistoryTurn> history = new ArrayList<>();
        var totalCost = java.math.BigDecimal.ZERO;
        for (int turn = 1; turn <= agent.get().getMaxTurns(); turn++) {
            var input = collectInput(driver, isChat);
            if (input == null) {
                // Cliente desligou/fechou o canal — não há mais ninguém para escalar; encerra sem
                // chamar transferToQueue nem end() (mesmo padrão de ColetarTextoNodeHandler).
                return Optional.empty();
            }
            if (input.isBlank()) {
                // Timeout sem fala/digitação, mas o canal ainda está de pé — trata como
                // estagnação da conversa, escala para um humano.
                return escalate(agent.get(), driver);
            }

            ConversationResult result;
            try {
                result = conversationService.converse(agent.get(), history, input, apiKey);
            } catch (Exception e) {
                log.warn("Falha ao gerar resposta do agente_ia (causa={}) — escalando.", e.getClass().getSimpleName());
                persistTurn(agent.get(), isChat, context.channelId(), input, null, false, 0, 0);
                return escalate(agent.get(), driver);
            }

            driver.playMessage(null, result.answerText());
            persistTurn(
                    agent.get(),
                    isChat,
                    context.channelId(),
                    input,
                    result.answerText(),
                    true,
                    result.inputTokens(),
                    result.outputTokens());
            history.add(new HistoryTurn(input, result.answerText()));
            totalCost = totalCost.add(result.costUsd());

            if (result.completed()) {
                var outgoing = graph.outgoingEdges(node.id());
                if (!outgoing.isEmpty()) {
                    return Optional.of(outgoing.get(0));
                }
                driver.end();
                return Optional.empty();
            }

            if (totalCost.compareTo(agent.get().getMaxCostUsd()) > 0) {
                log.info(
                        "agente_ia \"{}\" ultrapassou maxCostUsd nesta conversa — escalando.",
                        agent.get().getName());
                return escalate(agent.get(), driver);
            }
        }
        // maxTurns atingido sem conclusão natural — escala.
        return escalate(agent.get(), driver);
    }

    private String collectInput(ChannelDriver driver, boolean isChat) {
        if (isChat) {
            var result = driver.collectText(CHAT_COLLECT_TIMEOUT);
            if (result.outcome() == ChannelDriver.TextResult.Outcome.HUNG_UP) {
                return null;
            }
            return result.outcome() == ChannelDriver.TextResult.Outcome.COLLECTED ? result.text() : "";
        }
        var recorded = driver.recordResponse(VOICE_RECORD_DURATION);
        if (recorded.outcome() == ChannelDriver.RecordResult.Outcome.HUNG_UP) {
            return null;
        }
        return transcribeQuietly(recorded.audioPath());
    }

    private String transcribeQuietly(String audioPath) {
        if (audioPath == null) {
            return "";
        }
        try {
            var transcript = identityResolver.transcribe(Files.readAllBytes(Path.of(audioPath))).transcript();
            return transcript == null ? "" : transcript;
        } catch (IOException e) {
            log.warn("Áudio de agente_ia não encontrado/legível (path={}).", audioPath);
            return "";
        }
    }

    private void persistTurn(
            CcIaAgent agent,
            boolean isChat,
            String channelId,
            String question,
            String answer,
            boolean matched,
            int inputTokens,
            int outputTokens) {
        turnRepository.save(
                CcIaAgentTurn.builder()
                        .agent(agent)
                        .channel(isChat ? "chat" : "voice")
                        .correlationRef(channelId)
                        .question(question)
                        .answer(answer)
                        .matched(matched)
                        .model(agent.getModel())
                        .inputTokens(inputTokens)
                        .outputTokens(outputTokens)
                        .build());
    }

    private Optional<FlowGraph.Edge> escalate(CcIaAgent agent, ChannelDriver driver) {
        CcQueue queue = agent.getFallbackQueue();
        if (queue == null) {
            log.warn("agente_ia \"{}\" sem fila de fallback configurada — encerrando.", agent.getName());
            driver.end();
            return Optional.empty();
        }
        driver.transferToQueue(queue.getName());
        return Optional.empty();
    }

    private Optional<CcIaAgent> loadAgent(String configuracaoIaId) {
        if (configuracaoIaId == null || configuracaoIaId.isBlank()) {
            return Optional.empty();
        }
        try {
            return agentRepository.findById(Long.valueOf(configuracaoIaId.trim())).filter(CcIaAgent::getActive);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
