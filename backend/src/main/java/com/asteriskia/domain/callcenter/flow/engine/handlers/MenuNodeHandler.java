package com.asteriskia.domain.callcenter.flow.engine.handlers;

import com.asteriskia.domain.callcenter.flow.audio.CallCenterAudioService;
import com.asteriskia.domain.callcenter.flow.engine.ChannelDriver;
import com.asteriskia.domain.callcenter.flow.engine.FlowExecutionContext;
import com.asteriskia.domain.callcenter.flow.engine.FlowGraph;
import com.asteriskia.domain.callcenter.flow.engine.NodeHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * MenuNodeHandler — nó "menu_opcoes" (Fase 5b, ampliado na Fase 5c).
 *
 * <p><b>Grafos {@code schemaVersion 2} (Fase 5c)</b>: a propriedade {@code opcoesMenu} traz um
 * JSON {@code [{"digito":"1","rotulo":"Vendas"}, ...]} — o dígito é usado direto como escolha do
 * {@code promptChoice}, e a aresta a seguir é resolvida pelo {@code sourceHandle}
 * {@code "opt-<digito>"} (não mais por id de aresta digitado à mão). Ramos opcionais
 * {@code opt-timeout}/{@code opt-invalido} tornam timeout/dígito inválido não-terminais quando o
 * operador desenhar essas saídas; sem elas, mantém o comportamento seguro da 5b (encerra a
 * chamada, nunca deixa o canal preso em Stasis).
 *
 * <p><b>Grafos {@code schemaVersion 1}</b>: continuam lidos pelo parser antigo, propriedade
 * {@code opcoes} no formato {@code "1=edgeId1;2=edgeId2"} (dígito→id-da-aresta) — zero fluxos
 * publicados usam esse formato nesta VPS, mas o custo de manter o fallback é baixo.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MenuNodeHandler implements NodeHandler {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final int DEFAULT_TENTATIVAS = 3;
    private static final String HANDLE_TIMEOUT = "opt-timeout";
    private static final String HANDLE_INVALIDO = "opt-invalido";

    private final ObjectMapper objectMapper;
    private final CallCenterAudioService audioService;

    @Override
    public String nodeType() {
        return "menu_opcoes";
    }

    @Override
    public Optional<FlowGraph.Edge> handle(FlowGraph graph, FlowGraph.Node node, FlowExecutionContext context) {
        var opcoesMenu = parseOpcoesMenu(node.data().property("opcoesMenu"));
        if (!opcoesMenu.isEmpty()) {
            return handleV2(graph, node, context, opcoesMenu);
        }
        return handleV1Legacy(graph, node, context);
    }

    private Optional<FlowGraph.Edge> handleV2(
            FlowGraph graph, FlowGraph.Node node, FlowExecutionContext context, List<String> digitos) {
        var driver = context.driver();
        var audioPath = resolveAudioPath(node.data().property("audioPath"));
        var texto = node.data().property("texto");
        if ((audioPath != null && !audioPath.isBlank()) || (texto != null && !texto.isBlank())) {
            driver.playMessage(audioPath, texto);
        }

        var timeout = parseTimeout(node.data().property("timeoutSegundos"));
        var tentativasRestantes = parseTentativas(node.data().property("tentativas"));

        while (tentativasRestantes > 0) {
            var result = driver.promptChoice(digitos, timeout);
            switch (result.outcome()) {
                case CHOSEN -> {
                    return graph.outgoingEdge(node.id(), "opt-" + result.choice());
                }
                case HUNG_UP -> {
                    return Optional.empty();
                }
                case TIMEOUT -> {
                    return followOrEnd(graph, node, driver, HANDLE_TIMEOUT);
                }
                case INVALID -> {
                    tentativasRestantes--;
                    if (tentativasRestantes > 0) {
                        driver.playMessage(null, "Opção inválida, tente novamente.");
                    }
                }
            }
        }
        return followOrEnd(graph, node, driver, HANDLE_INVALIDO);
    }

    /** Sem aresta desenhada pro ramo pedido, mantém o comportamento seguro da 5b: encerra a
     * chamada em vez de deixar o canal preso em Stasis sem instrução nenhuma. */
    private Optional<FlowGraph.Edge> followOrEnd(
            FlowGraph graph, FlowGraph.Node node, ChannelDriver driver, String handle) {
        var edge = graph.outgoingEdge(node.id(), handle);
        if (edge.isEmpty()) {
            driver.end();
        }
        return edge;
    }

    /** Nulo se o id não existir mais na biblioteca — nunca trava a chamada por isso. */
    private String resolveAudioPath(String audioFileIdRaw) {
        if (audioFileIdRaw == null || audioFileIdRaw.isBlank()) {
            return null;
        }
        try {
            return audioService.resolveSoundPath(Long.parseLong(audioFileIdRaw.trim())).orElse(null);
        } catch (NumberFormatException e) {
            log.warn("Propriedade audioPath do nó menu_opcoes não é um id numérico válido: {}", audioFileIdRaw);
            return null;
        }
    }

    private Duration parseTimeout(String raw) {
        try {
            return raw == null || raw.isBlank() ? DEFAULT_TIMEOUT : Duration.ofSeconds(Long.parseLong(raw.trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_TIMEOUT;
        }
    }

    private int parseTentativas(String raw) {
        try {
            var value = raw == null || raw.isBlank() ? DEFAULT_TENTATIVAS : Integer.parseInt(raw.trim());
            return value > 0 ? value : DEFAULT_TENTATIVAS;
        } catch (NumberFormatException e) {
            return DEFAULT_TENTATIVAS;
        }
    }

    private List<String> parseOpcoesMenu(String opcoesMenuJson) {
        if (opcoesMenuJson == null || opcoesMenuJson.isBlank()) {
            return List.of();
        }
        try {
            var arrayNode = objectMapper.readTree(opcoesMenuJson);
            List<String> digitos = new ArrayList<>();
            if (arrayNode.isArray()) {
                for (JsonNode item : arrayNode) {
                    var digito = item.path("digito").asText(null);
                    if (digito != null && !digito.isBlank()) {
                        digitos.add(digito);
                    }
                }
            }
            return List.copyOf(digitos);
        } catch (Exception e) {
            log.warn("Propriedade opcoesMenu do nó {} não é um JSON válido, ignorando: {}", "menu_opcoes", e.getMessage());
            return List.of();
        }
    }

    // --- Compatibilidade com grafos schemaVersion 1 (formato "1=edgeId;2=edgeId2") ---

    private Optional<FlowGraph.Edge> handleV1Legacy(FlowGraph graph, FlowGraph.Node node, FlowExecutionContext context) {
        var mapping = parseOpcoesLegacy(node.data().property("opcoes"));
        if (mapping.isEmpty()) {
            return graph.outgoingEdges(node.id()).stream().findFirst();
        }
        var result = context.driver().promptChoice(List.copyOf(mapping.keySet()), DEFAULT_TIMEOUT);
        if (result.outcome() != ChannelDriver.PromptResult.Outcome.CHOSEN) {
            // Timeout/inválido/desistência sem dígito válido — grafo v1 não tem "aresta de
            // timeout" própria; encerrar é o comportamento seguro já validado na 5b.
            if (result.outcome() == ChannelDriver.PromptResult.Outcome.TIMEOUT
                    || result.outcome() == ChannelDriver.PromptResult.Outcome.INVALID) {
                context.driver().end();
            }
            return Optional.empty();
        }
        var edgeId = mapping.get(result.choice());
        return graph.outgoingEdges(node.id()).stream().filter(e -> e.id().equals(edgeId)).findFirst();
    }

    private Map<String, String> parseOpcoesLegacy(String opcoes) {
        if (opcoes == null || opcoes.isBlank()) {
            return Map.of();
        }
        return java.util.Arrays.stream(opcoes.split(";"))
                .map(String::trim)
                .filter(part -> part.contains("="))
                .collect(
                        Collectors.toMap(
                                part -> part.substring(0, part.indexOf('=')).trim(),
                                part -> part.substring(part.indexOf('=') + 1).trim(),
                                (a, b) -> a,
                                java.util.LinkedHashMap::new));
    }
}
