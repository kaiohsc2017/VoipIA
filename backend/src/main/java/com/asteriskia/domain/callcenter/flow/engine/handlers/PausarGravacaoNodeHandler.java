package com.asteriskia.domain.callcenter.flow.engine.handlers;

import com.asteriskia.domain.callcenter.flow.engine.FlowExecutionContext;
import com.asteriskia.domain.callcenter.flow.engine.FlowGraph;
import com.asteriskia.domain.callcenter.flow.engine.NodeHandler;
import com.asteriskia.domain.callcenter.recording.CallCenterRecordingControlService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PausarGravacaoNodeHandler — nó "pausar_gravacao" (Fase 5c), destrava
 * {@link CallCenterRecordingControlService} (pronto desde a Fase 3, sem consumidor até aqui).
 * Fecha o requisito PCI/LGPD de não gravar coleta de dado sensível (ex.: número de cartão) durante
 * um trecho do atendimento.
 *
 * <p>Nunca bloqueante: falha ao pausar/retomar (AMI fora do ar, canal já caiu) só loga — o fluxo
 * segue para a próxima aresta normalmente, mesmo padrão fail-open já usado no {@code MixMonitor}
 * da gravação principal.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PausarGravacaoNodeHandler implements NodeHandler {

    private static final String ACAO_PAUSAR = "pausar";
    private static final String ACAO_RETOMAR = "retomar";

    private final CallCenterRecordingControlService recordingControlService;

    @Override
    public String nodeType() {
        return "pausar_gravacao";
    }

    @Override
    public Optional<FlowGraph.Edge> handle(FlowGraph graph, FlowGraph.Node node, FlowExecutionContext context) {
        var acao = node.data().property("acao");
        var channelName = context.driver().getVariable("CHANNEL(name)");
        if (channelName == null || channelName.isBlank()) {
            log.warn("Nó pausar_gravacao: não foi possível resolver o nome do canal, ação '{}' ignorada.", acao);
        } else if (ACAO_PAUSAR.equalsIgnoreCase(acao)) {
            recordingControlService.pause(channelName);
        } else if (ACAO_RETOMAR.equalsIgnoreCase(acao)) {
            recordingControlService.resume(channelName);
        } else {
            log.warn("Nó pausar_gravacao com ação desconhecida: '{}' — nenhuma ação tomada.", acao);
        }
        return graph.outgoingEdges(node.id()).stream().findFirst();
    }
}
