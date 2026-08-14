package com.asteriskia.domain.callcenter.flow.engine.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.businesshours.BusinessHoursService;
import com.asteriskia.domain.callcenter.flow.engine.ChannelDriver;
import com.asteriskia.domain.callcenter.flow.engine.FlowExecutionContext;
import com.asteriskia.domain.callcenter.flow.engine.FlowGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** HorarioFuncionamentoNodeHandlerTest — roteamento pelos 3 handles fixos (Fase 5e.1, V74). */
class HorarioFuncionamentoNodeHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final BusinessHoursService businessHoursService = mock(BusinessHoursService.class);
    private final HorarioFuncionamentoNodeHandler handler = new HorarioFuncionamentoNodeHandler(businessHoursService);

    private FlowGraph.Node node(String calendarioId) throws Exception {
        var propJson = calendarioId == null ? "{}" : "{\"calendarioId\":\"" + calendarioId + "\"}";
        var json = "{\"id\":\"n1\",\"type\":\"generic\",\"data\":{\"nodeType\":\"horario_funcionamento\",\"properties\":" + propJson + "}}";
        return mapper.readValue(json, FlowGraph.Node.class);
    }

    @Test
    void aberto_seguePeloHandleHrAberto() throws Exception {
        var node = node("1");
        when(businessHoursService.isOpen(eq(1L), any())).thenReturn(BusinessHoursService.Status.ABERTO);
        var edges = List.of(new FlowGraph.Edge("e1", "n1", "n2", "hr-aberto"), new FlowGraph.Edge("e2", "n1", "n3", "hr-fechado"));
        var context = new FlowExecutionContext(1L, 1L, 1L, "chan-1", mock(ChannelDriver.class));

        var result = handler.handle(new FlowGraph(2, List.of(node), edges), node, context);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo("e1");
    }

    @Test
    void fechadoPorHorario_seguePeloHandleHrFechado() throws Exception {
        var node = node("1");
        when(businessHoursService.isOpen(eq(1L), any())).thenReturn(BusinessHoursService.Status.FECHADO_HORARIO);
        var edges = List.of(new FlowGraph.Edge("e1", "n1", "n2", "hr-aberto"), new FlowGraph.Edge("e2", "n1", "n3", "hr-fechado"));
        var context = new FlowExecutionContext(1L, 1L, 1L, "chan-1", mock(ChannelDriver.class));

        var result = handler.handle(new FlowGraph(2, List.of(node), edges), node, context);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo("e2");
    }

    @Test
    void fechadoPorFeriado_seguePeloHandleHrFeriado() throws Exception {
        var node = node("1");
        when(businessHoursService.isOpen(eq(1L), any())).thenReturn(BusinessHoursService.Status.FECHADO_FERIADO);
        var edges = List.of(new FlowGraph.Edge("e3", "n1", "n4", "hr-feriado"));
        var context = new FlowExecutionContext(1L, 1L, 1L, "chan-1", mock(ChannelDriver.class));

        var result = handler.handle(new FlowGraph(2, List.of(node), edges), node, context);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo("e3");
    }

    @Test
    void semArestaParaOHandleResolvido_encerraChamada() throws Exception {
        var node = node("1");
        when(businessHoursService.isOpen(eq(1L), any())).thenReturn(BusinessHoursService.Status.FECHADO_HORARIO);
        var driver = mock(ChannelDriver.class);
        var context = new FlowExecutionContext(1L, 1L, 1L, "chan-1", driver);

        var result = handler.handle(new FlowGraph(2, List.of(node), List.of()), node, context);

        assertThat(result).isEmpty();
        verify(driver).end();
    }

    @Test
    void semCalendarioConfigurado_chamaServicoComNulo() throws Exception {
        var node = node(null);
        when(businessHoursService.isOpen(isNull(), any())).thenReturn(BusinessHoursService.Status.ABERTO);
        var edges = List.of(new FlowGraph.Edge("e1", "n1", "n2", "hr-aberto"));
        var context = new FlowExecutionContext(1L, 1L, 1L, "chan-1", mock(ChannelDriver.class));

        var result = handler.handle(new FlowGraph(2, List.of(node), edges), node, context);

        assertThat(result).isPresent();
    }
}
