package com.asteriskia.domain.callcenter.flow.engine.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.CcQueueRepository;
import com.asteriskia.domain.callcenter.businesshours.BusinessHoursService;
import com.asteriskia.domain.callcenter.flow.CcFlow;
import com.asteriskia.domain.callcenter.flow.CcFlowRepository;
import com.asteriskia.domain.callcenter.flow.CcFlowVersion;
import com.asteriskia.domain.callcenter.flow.CcFlowVersionRepository;
import com.asteriskia.domain.callcenter.flow.FlowStatus;
import com.asteriskia.domain.callcenter.flow.engine.CcFlowExecutionRepository;
import com.asteriskia.domain.callcenter.flow.engine.CcFlowExecutionStepRepository;
import com.asteriskia.domain.callcenter.flow.engine.ChannelDriver;
import com.asteriskia.domain.callcenter.flow.engine.FlowExecutionEngine;
import com.asteriskia.domain.callcenter.flow.engine.FlowExecutionTraceService;
import com.asteriskia.domain.callcenter.flow.engine.NodeHandler;
import com.asteriskia.domain.callcenter.flow.simulation.FlowSimulationRequest;
import com.asteriskia.domain.callcenter.flow.simulation.FlowSimulationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * HorarioFuncionamentoDualRuntimeTest — prova que o nó "horario_funcionamento" roteia igual no
 * motor real ({@link FlowExecutionEngine}) e no simulador ({@link FlowSimulationService}), Fase
 * 5e.1 — os dois despacham pelo mesmo bean {@link HorarioFuncionamentoNodeHandler}, injetado sem
 * nenhuma cópia de lógica (requisito explícito da tarefa: rodar em ambos sem gambiarra).
 */
@ExtendWith(MockitoExtension.class)
class HorarioFuncionamentoDualRuntimeTest {

    @Mock private CcFlowRepository flowRepository;
    @Mock private CcFlowVersionRepository flowVersionRepository;
    @Mock private CcQueueRepository queueRepository;
    @Mock private CcFlowExecutionRepository executionRepository;
    @Mock private CcFlowExecutionStepRepository stepRepository;
    @Mock private BusinessHoursService businessHoursService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private FlowExecutionTraceService traceService;
    private HorarioFuncionamentoNodeHandler handler;

    private static final String GRAPH_JSON =
            "{\"schemaVersion\":2,\"nodes\":["
                    + "{\"id\":\"n1\",\"type\":\"generic\",\"data\":{\"nodeType\":\"inicio\",\"properties\":{}}},"
                    + "{\"id\":\"n2\",\"type\":\"generic\",\"data\":{\"nodeType\":\"horario_funcionamento\",\"properties\":{\"calendarioId\":\"1\"}}},"
                    + "{\"id\":\"n3\",\"type\":\"generic\",\"data\":{\"nodeType\":\"encerrar\",\"properties\":{}}}"
                    + "],\"edges\":["
                    + "{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\"},"
                    + "{\"id\":\"e2\",\"source\":\"n2\",\"target\":\"n3\",\"sourceHandle\":\"hr-fechado\"}"
                    + "]}";

    @BeforeEach
    void setUp() {
        var executionIds = new AtomicLong(1);
        var stepIds = new AtomicLong(1);
        lenient()
                .when(executionRepository.save(any()))
                .thenAnswer(
                        inv -> {
                            var e = (com.asteriskia.domain.callcenter.flow.engine.CcFlowExecution) inv.getArgument(0);
                            if (e.getId() == null) {
                                e.setId(executionIds.getAndIncrement());
                            }
                            return e;
                        });
        lenient()
                .when(stepRepository.save(any()))
                .thenAnswer(
                        inv -> {
                            var s = (com.asteriskia.domain.callcenter.flow.engine.CcFlowExecutionStep) inv.getArgument(0);
                            if (s.getId() == null) {
                                s.setId(stepIds.getAndIncrement());
                            }
                            return s;
                        });
        traceService = new FlowExecutionTraceService(executionRepository, stepRepository);
        handler = new HorarioFuncionamentoNodeHandler(businessHoursService);
        lenient().when(businessHoursService.isOpen(eq(1L), any())).thenReturn(BusinessHoursService.Status.FECHADO_HORARIO);
    }

    private List<NodeHandler> handlers() {
        return new ArrayList<>(
                List.of(
                        new com.asteriskia.domain.callcenter.flow.engine.handlers.StartNodeHandler(),
                        handler,
                        new com.asteriskia.domain.callcenter.flow.engine.handlers.HangupNodeHandler()));
    }

    @Test
    void motorReal_seguePeloHandleFechadoAteEncerrar() {
        var flow = CcFlow.builder().id(1L).name("f-6001").entryExtension("6001").publishedVersionId(10L).build();
        var version =
                CcFlowVersion.builder().id(10L).flow(flow).versionNumber(1).status(FlowStatus.PUBLISHED).graph(GRAPH_JSON).build();
        lenient().when(flowRepository.findByEntryExtension("6001")).thenReturn(Optional.of(flow));
        lenient().when(flowVersionRepository.findById(10L)).thenReturn(Optional.of(version));

        var engine = new FlowExecutionEngine(flowRepository, flowVersionRepository, traceService, handlers(), objectMapper);
        var driver = mock(ChannelDriver.class);

        engine.start("chan-1", "6001", "uid-1", driver);

        org.mockito.Mockito.verify(driver).end();
    }

    @Test
    void simulador_mesmoGrafo_mesmoHandlerSemDuplicarLogica() {
        var flow = CcFlow.builder().id(2L).name("f-sim").entryExtension("6002").build();
        var version =
                CcFlowVersion.builder().id(20L).flow(flow).versionNumber(1).status(FlowStatus.DRAFT).graph(GRAPH_JSON).build();
        lenient().when(flowRepository.findById(2L)).thenReturn(Optional.of(flow));
        lenient().when(flowVersionRepository.findByFlowIdAndStatus(2L, FlowStatus.DRAFT)).thenReturn(Optional.of(version));

        var simulationService = new FlowSimulationService(flowRepository, flowVersionRepository, handlers(), objectMapper);
        var result = simulationService.simulate(2L, new FlowSimulationRequest(Map.of(), List.of()));

        // Mesma decisão do motor real (FECHADO_HORARIO → encerra), sem persistir nada em
        // cc_flow_executions/_steps (traceService nunca é chamado pelo simulador).
        assertThat(result.outcome()).isEqualTo("COMPLETED");
        org.mockito.Mockito.verifyNoInteractions(executionRepository, stepRepository);
    }
}
