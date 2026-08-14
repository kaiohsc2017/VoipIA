package com.asteriskia.domain.callcenter.flow.engine.handlers;

import com.asteriskia.domain.callcenter.flow.engine.FlowExecutionContext;
import com.asteriskia.domain.callcenter.flow.engine.FlowGraph;
import com.asteriskia.domain.callcenter.flow.engine.NodeHandler;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * TransferToExtensionNodeHandler — nó "transferir_ramal" (Fase 5e.2), exclusivo do canal voz.
 * Encaminha via {@code ChannelDriver.transferToExtension}, que por trás faz
 * {@code continueInDialplan} para o ramal informado.
 *
 * <p>Validação estrita ANTES de qualquer uso do valor (mesma classe do achado de segurança de
 * {@code AriClient.play} — Fase 10, H1): a propriedade {@code ramal} chega de um nó de fluxo
 * editável pela UI ({@code PERM_WRITE_callcenter.fluxos}), nunca confiável sem checagem. Só
 * aceita dígitos (3 a 4 caracteres — cobre ramal de agente 4xxx, fila 5xxx e os ramais internos
 * fixos 1000-1002/9xxx), nunca sintaxe de função de dialplan, path ou separador. A validação é
 * repetida em {@code AriVoiceChannelDriver.transferToExtension} (defesa em profundidade), mas o
 * handler nunca delega a validação só à camada de baixo.
 */
@Slf4j
@Component
public class TransferToExtensionNodeHandler implements NodeHandler {

    private static final Pattern SAFE_EXTENSION = Pattern.compile("^[0-9]{3,4}$");

    @Override
    public String nodeType() {
        return "transferir_ramal";
    }

    @Override
    public Optional<FlowGraph.Edge> handle(FlowGraph graph, FlowGraph.Node node, FlowExecutionContext context) {
        var ramal = node.data().property("ramal");
        var normalizado = ramal == null ? null : ramal.trim();
        if (normalizado == null || !SAFE_EXTENSION.matcher(normalizado).matches()) {
            log.warn("Nó transferir_ramal com ramal inválido/fora do allowlist (ramal={}) — encerrando chamada.", ramal);
            context.driver().end();
            return Optional.empty();
        }
        context.driver().transferToExtension(normalizado);
        return Optional.empty();
    }
}
