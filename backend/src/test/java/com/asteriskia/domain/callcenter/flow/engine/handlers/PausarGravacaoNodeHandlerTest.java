package com.asteriskia.domain.callcenter.flow.engine.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.flow.engine.ChannelDriver;
import com.asteriskia.domain.callcenter.flow.engine.FlowExecutionContext;
import com.asteriskia.domain.callcenter.flow.engine.FlowGraph;
import com.asteriskia.domain.callcenter.recording.CallCenterRecordingControlService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class PausarGravacaoNodeHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CallCenterRecordingControlService recordingControlService = mock(CallCenterRecordingControlService.class);
    private final PausarGravacaoNodeHandler handler = new PausarGravacaoNodeHandler(recordingControlService);

    private FlowGraph.Node node(String acao) throws Exception {
        var json = "{\"id\":\"n2\",\"type\":\"generic\",\"data\":{\"nodeType\":\"pausar_gravacao\",\"properties\":{\"acao\":\"" + acao + "\"}}}";
        return mapper.readValue(json, FlowGraph.Node.class);
    }

    @Test
    void acaoPausar_chamaPauseComNomeDoCanal() throws Exception {
        var node = node("pausar");
        var driver = mock(ChannelDriver.class);
        when(driver.getVariable("CHANNEL(name)")).thenReturn("PJSIP/4001-0000001a");
        var edges = List.of(new FlowGraph.Edge("e1", "n2", "n3"));
        var context = new FlowExecutionContext(1L, 1L, 1L, "chan-1", driver);

        var result = handler.handle(new FlowGraph(2, List.of(node), edges), node, context);

        verify(recordingControlService).pause("PJSIP/4001-0000001a");
        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo("e1");
    }

    @Test
    void acaoRetomar_chamaResume() throws Exception {
        var node = node("retomar");
        var driver = mock(ChannelDriver.class);
        when(driver.getVariable("CHANNEL(name)")).thenReturn("PJSIP/4001-0000001a");
        var context = new FlowExecutionContext(1L, 1L, 1L, "chan-1", driver);

        handler.handle(new FlowGraph(2, List.of(node), List.of()), node, context);

        verify(recordingControlService).resume("PJSIP/4001-0000001a");
    }

    @Test
    void semNomeDoCanal_naoChamaServicoENaoQuebraOFluxo() throws Exception {
        var node = node("pausar");
        var driver = mock(ChannelDriver.class);
        when(driver.getVariable("CHANNEL(name)")).thenReturn(null);
        var edges = List.of(new FlowGraph.Edge("e1", "n2", "n3"));
        var context = new FlowExecutionContext(1L, 1L, 1L, "chan-1", driver);

        var result = handler.handle(new FlowGraph(2, List.of(node), edges), node, context);

        verify(recordingControlService, never()).pause(org.mockito.ArgumentMatchers.anyString());
        assertThat(result).isPresent();
    }
}
