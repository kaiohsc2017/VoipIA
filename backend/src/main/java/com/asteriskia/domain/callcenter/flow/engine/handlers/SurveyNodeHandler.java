package com.asteriskia.domain.callcenter.flow.engine.handlers;

import com.asteriskia.domain.callcenter.flow.engine.FlowExecutionContext;
import com.asteriskia.domain.callcenter.flow.engine.FlowGraph;
import com.asteriskia.domain.callcenter.flow.engine.NodeHandler;
import com.asteriskia.domain.callcenter.nps.CallCenterSurveyRunner;
import com.asteriskia.domain.callcenter.nps.CcSurveyRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * SurveyNodeHandler — nó "pesquisa_satisfacao" (Fase 21). A propriedade {@code pesquisaId} guarda
 * o id de {@code cc_surveys}. Sem pesquisa válida configurada, ou pesquisa inativa, segue direto
 * para a próxima aresta sem executar nada — mesmo comportamento fail-open dos demais nós (nunca
 * prende o canal). A execução em si (perguntas/respostas/nota) é delegada a
 * {@link CallCenterSurveyRunner}, compartilhada com o disparo direto pós-fila
 * ({@code CallCenterNpsExecutionService}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SurveyNodeHandler implements NodeHandler {

    private final CcSurveyRepository surveyRepository;
    private final CallCenterSurveyRunner runner;

    @Override
    public String nodeType() {
        return "pesquisa_satisfacao";
    }

    @Override
    public Optional<FlowGraph.Edge> handle(FlowGraph graph, FlowGraph.Node node, FlowExecutionContext context) {
        var pesquisaId = node.data().property("pesquisaId");
        var survey = pesquisaId == null ? null : parseAndFind(pesquisaId);
        if (survey == null || !Boolean.TRUE.equals(survey.getActive())) {
            log.warn("Nó pesquisa_satisfacao sem pesquisa ativa configurada (pesquisaId={}) — pulando.", pesquisaId);
        } else {
            runner.run(survey, context.driver(), context.channelId(), null);
        }
        return graph.outgoingEdges(node.id()).stream().findFirst();
    }

    private com.asteriskia.domain.callcenter.nps.CcSurvey parseAndFind(String pesquisaId) {
        try {
            return surveyRepository.findById(Long.valueOf(pesquisaId)).orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
