package com.asteriskia.domain.callcenter.flow.engine.handlers;

import com.asteriskia.domain.callcenter.businesshours.BusinessHoursService;
import com.asteriskia.domain.callcenter.flow.engine.FlowExecutionContext;
import com.asteriskia.domain.callcenter.flow.engine.FlowGraph;
import com.asteriskia.domain.callcenter.flow.engine.NodeHandler;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * HorarioFuncionamentoNodeHandler — nó "horario_funcionamento" (Fase 5e.1 do plano de fechamento
 * 5/7/9, desbloqueado nesta entrega). 3 handles de saída fixos ({@code hr-aberto}/{@code
 * hr-fechado}/{@code hr-feriado}), mesmo padrão de {@code sourceHandle} nomeado introduzido pelo
 * {@code menu_opcoes} na Fase 5c — mas com saídas fixas (não dinâmicas por configuração), como
 * o nó "condição".
 *
 * <p>Roda sem alteração dentro do {@code FlowExecutionEngine} real e do {@code
 * FlowSimulationService} (Fase 5d) — os dois despacham pelo mesmo {@link NodeHandler}, e a
 * consulta ao banco ({@link BusinessHoursService}) funciona igual nos dois contextos (a
 * simulação só evita chamada de IA, não bloqueia consulta a calendário/feriado).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HorarioFuncionamentoNodeHandler implements NodeHandler {

    private static final String HANDLE_ABERTO = "hr-aberto";
    private static final String HANDLE_FECHADO = "hr-fechado";
    private static final String HANDLE_FERIADO = "hr-feriado";

    private final BusinessHoursService businessHoursService;

    @Override
    public String nodeType() {
        return "horario_funcionamento";
    }

    @Override
    public Optional<FlowGraph.Edge> handle(FlowGraph graph, FlowGraph.Node node, FlowExecutionContext context) {
        var calendarId = parseCalendarId(node.data().property("calendarioId"));
        var status = businessHoursService.isOpen(calendarId, Instant.now());
        var handle =
                switch (status) {
                    case ABERTO -> HANDLE_ABERTO;
                    case FECHADO_HORARIO -> HANDLE_FECHADO;
                    case FECHADO_FERIADO -> HANDLE_FERIADO;
                };
        return followOrEnd(graph, node, context, handle);
    }

    /** Sem aresta desenhada pro ramo resolvido, encerra a chamada — mesmo comportamento seguro já
     * usado por {@code MenuNodeHandler} para nunca deixar o canal preso em Stasis. */
    private Optional<FlowGraph.Edge> followOrEnd(
            FlowGraph graph, FlowGraph.Node node, FlowExecutionContext context, String handle) {
        var edge = graph.outgoingEdge(node.id(), handle);
        if (edge.isEmpty()) {
            context.driver().end();
        }
        return edge;
    }

    /** Nulo se a propriedade não estiver configurada ou não for um id numérico válido — {@link
     * BusinessHoursService#isOpen} trata {@code null} como "sempre aberto" (fail-open). */
    private Long parseCalendarId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("Propriedade calendarioId do nó horario_funcionamento não é um id numérico válido: {}", raw);
            return null;
        }
    }
}
