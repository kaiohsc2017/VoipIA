package com.asteriskia.domain.callcenter.flow.engine.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.asteriskia.domain.callcenter.flow.engine.ChannelDriver;
import com.asteriskia.domain.callcenter.flow.engine.FlowExecutionContext;
import com.asteriskia.domain.callcenter.flow.engine.FlowGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * TransferToExtensionNodeHandlerTest — nó "transferir_ramal" (Fase 5e.2). Cobre a validação
 * estrita do ramal (mesma classe do achado de segurança de {@code AriClient.play}) antes de
 * delegar a {@code ChannelDriver.transferToExtension}.
 */
class TransferToExtensionNodeHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TransferToExtensionNodeHandler handler = new TransferToExtensionNodeHandler();

    private FlowGraph.Node node(String ramal) throws Exception {
        var propriedades = ramal == null ? "{}" : "{\"ramal\":\"" + ramal + "\"}";
        var json = "{\"id\":\"n2\",\"type\":\"generic\",\"data\":{\"nodeType\":\"transferir_ramal\",\"properties\":" + propriedades + "}}";
        return mapper.readValue(json, FlowGraph.Node.class);
    }

    @Test
    void ramalValido_delegaParaODriver() throws Exception {
        var node = node("4001");
        var driver = mock(ChannelDriver.class);
        var context = new FlowExecutionContext(1L, 1L, 1L, "chan-1", driver);

        var result = handler.handle(new FlowGraph(2, List.of(node), List.of()), node, context);

        verify(driver).transferToExtension("4001");
        assertThat(result).isEmpty();
    }

    @Test
    void ramalComEspacos_normalizaAntesDeDelegar() throws Exception {
        var node = node(" 4001 ");
        var driver = mock(ChannelDriver.class);
        var context = new FlowExecutionContext(1L, 1L, 1L, "chan-1", driver);

        handler.handle(new FlowGraph(2, List.of(node), List.of()), node, context);

        verify(driver).transferToExtension("4001");
    }

    @Test
    void ramalInvalido_encerraChamadaENuncaDelegaAoDriver() throws Exception {
        var node = node("FILE(/etc/asterisk/manager.conf,0,0)");
        var driver = mock(ChannelDriver.class);
        var context = new FlowExecutionContext(1L, 1L, 1L, "chan-1", driver);

        var result = handler.handle(new FlowGraph(2, List.of(node), List.of()), node, context);

        verify(driver).end();
        verify(driver, never()).transferToExtension(org.mockito.ArgumentMatchers.anyString());
        assertThat(result).isEmpty();
    }

    @Test
    void semPropriedadeRamal_encerraChamada() throws Exception {
        var node = node(null);
        var driver = mock(ChannelDriver.class);
        var context = new FlowExecutionContext(1L, 1L, 1L, "chan-1", driver);

        handler.handle(new FlowGraph(2, List.of(node), List.of()), node, context);

        verify(driver).end();
        verify(driver, never()).transferToExtension(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void nodeType_retornaTransferirRamal() {
        assertThat(handler.nodeType()).isEqualTo("transferir_ramal");
    }
}
