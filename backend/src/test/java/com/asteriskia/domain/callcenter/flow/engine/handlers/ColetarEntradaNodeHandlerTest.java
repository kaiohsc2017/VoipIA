package com.asteriskia.domain.callcenter.flow.engine.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.flow.engine.ChannelDriver;
import com.asteriskia.domain.callcenter.flow.engine.FlowExecutionContext;
import com.asteriskia.domain.callcenter.flow.engine.FlowGraph;
import com.asteriskia.domain.callcenter.identity.CallCenterIdentityResolver;
import com.asteriskia.domain.callcenter.identity.IdentitySource;
import com.asteriskia.domain.callcenter.identity.ResolvedIdentity;
import com.asteriskia.domain.callcenter.interaction.CcInteraction;
import com.asteriskia.domain.callcenter.interaction.CcInteractionRepository;
import com.asteriskia.integration.ad.AdUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ColetarEntradaNodeHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ColetarEntradaNodeHandler handler;
    private ChannelDriver driver;
    private FlowExecutionContext context;
    private CallCenterIdentityResolver identityResolver;
    private CcInteractionRepository interactionRepository;
    private String recordedAudioPath;

    @BeforeEach
    void setUp() throws Exception {
        identityResolver = mock(CallCenterIdentityResolver.class);
        interactionRepository = mock(CcInteractionRepository.class);
        handler = new ColetarEntradaNodeHandler(identityResolver, interactionRepository);
        driver = mock(ChannelDriver.class);
        context = new FlowExecutionContext(1L, 1L, 1L, "channel-abc", driver);

        var tempFile = Files.createTempFile("coletar-entrada-test", ".wav");
        Files.write(tempFile, new byte[] {9, 9, 9});
        recordedAudioPath = tempFile.toAbsolutePath().toString();
    }

    private FlowGraph.Node nodeWith(String variavel, String sensivel, String identificarContato) throws Exception {
        var props = new java.util.LinkedHashMap<String, Object>();
        if (variavel != null) props.put("variavel", variavel);
        if (sensivel != null) props.put("sensivel", sensivel);
        if (identificarContato != null) props.put("identificarContato", identificarContato);
        var json = "{\"id\":\"n1\",\"type\":\"generic\",\"data\":{\"nodeType\":\"coletar_entrada\",\"properties\":"
                + mapper.writeValueAsString(props) + "}}";
        return mapper.readValue(json, FlowGraph.Node.class);
    }

    @Test
    @DisplayName("hung up durante a gravação não segue nenhuma aresta e não chama identificação")
    void hungUp_returnsEmptyWithoutIdentity() throws Exception {
        when(driver.recordResponse(any())).thenReturn(ChannelDriver.RecordResult.hungUp());
        var node = nodeWith("login", null, "true");
        var graph = new FlowGraph(2, List.of(node), List.of());

        var edge = handler.handle(graph, node, context);

        assertThat(edge).isEmpty();
        verify(identityResolver, never()).findFuzzyCandidateByName(anyString());
        verify(driver, never()).end();
    }

    @Test
    @DisplayName("transcrição coletada é gravada na variável do contexto e do driver")
    void collected_setsVariable() throws Exception {
        when(driver.recordResponse(Duration.ofSeconds(15)))
                .thenReturn(ChannelDriver.RecordResult.recorded(recordedAudioPath));
        when(identityResolver.transcribe(any())).thenReturn(new CallCenterIdentityResolver.TranscriptionResult("jsilva", BigDecimal.ZERO));
        var node = nodeWith("login", null, null);
        var graph = new FlowGraph(2, List.of(node), List.of(new FlowGraph.Edge("e1", "n1", "n2")));

        var edge = handler.handle(graph, node, context);

        assertThat(edge).isPresent();
        assertThat(context.getVariable("login")).isEqualTo("jsilva");
        verify(driver).setVariable("login", "jsilva");
    }

    @Test
    @DisplayName("identificarContato=true com confirmação positiva persiste resolved_ad_sam na interação")
    void identifyContact_confirmedPositive_persistsIdentity() throws Exception {
        when(driver.recordResponse(Duration.ofSeconds(15)))
                .thenReturn(ChannelDriver.RecordResult.recorded(recordedAudioPath));
        when(driver.recordResponse(Duration.ofSeconds(8)))
                .thenReturn(ChannelDriver.RecordResult.recorded(recordedAudioPath));
        when(identityResolver.transcribe(any()))
                .thenReturn(new CallCenterIdentityResolver.TranscriptionResult("João Silva", BigDecimal.ZERO))
                .thenReturn(new CallCenterIdentityResolver.TranscriptionResult("sim", BigDecimal.valueOf(0.001)));
        var adUser = AdUser.builder().id(1L).samAccountName("jsilva").displayName("João Silva").build();
        when(identityResolver.findFuzzyCandidateByName("João Silva"))
                .thenReturn(Optional.of(ResolvedIdentity.fuzzy(adUser, 0.6)));
        when(identityResolver.isSpokenConfirmationPositive("sim")).thenReturn(true);
        var interaction = CcInteraction.builder().id(5L).channelUniqueId("channel-abc").build();
        when(interactionRepository.findByChannelUniqueId("channel-abc")).thenReturn(Optional.of(interaction));

        var node = nodeWith(null, null, "true");
        var graph = new FlowGraph(2, List.of(node), List.of(new FlowGraph.Edge("e1", "n1", "n2")));

        handler.handle(graph, node, context);

        assertThat(interaction.getResolvedAdSam()).isEqualTo("jsilva");
        assertThat(interaction.getIdentitySource()).isEqualTo(IdentitySource.URA_INPUT.name());
        verify(interactionRepository).save(interaction);
        verify(identityResolver).logResolution("voice", "resolved", BigDecimal.valueOf(0.001));
    }

    @Test
    @DisplayName("identificarContato=true com confirmação negativa nunca persiste identidade (fail-closed)")
    void identifyContact_confirmedNegative_doesNotPersist() throws Exception {
        when(driver.recordResponse(Duration.ofSeconds(15)))
                .thenReturn(ChannelDriver.RecordResult.recorded(recordedAudioPath));
        when(driver.recordResponse(Duration.ofSeconds(8)))
                .thenReturn(ChannelDriver.RecordResult.recorded(recordedAudioPath));
        when(identityResolver.transcribe(any()))
                .thenReturn(new CallCenterIdentityResolver.TranscriptionResult("Maria Souza", BigDecimal.ZERO))
                .thenReturn(new CallCenterIdentityResolver.TranscriptionResult("não", BigDecimal.ZERO));
        var adUser = AdUser.builder().id(2L).samAccountName("outra").displayName("Outra Pessoa").build();
        when(identityResolver.findFuzzyCandidateByName("Maria Souza"))
                .thenReturn(Optional.of(ResolvedIdentity.fuzzy(adUser, 0.5)));
        when(identityResolver.isSpokenConfirmationPositive("não")).thenReturn(false);

        var node = nodeWith(null, null, "true");
        var graph = new FlowGraph(2, List.of(node), List.of(new FlowGraph.Edge("e1", "n1", "n2")));

        handler.handle(graph, node, context);

        verify(interactionRepository, never()).save(any());
        verify(identityResolver).logResolution("voice", "rejected", BigDecimal.ZERO);
    }

    @Test
    @DisplayName("identificarContato=true sem candidato acima do limiar registra unresolved sem falhar o fluxo")
    void identifyContact_noCandidate_logsUnresolved() throws Exception {
        when(driver.recordResponse(Duration.ofSeconds(15)))
                .thenReturn(ChannelDriver.RecordResult.recorded(recordedAudioPath));
        when(identityResolver.transcribe(any()))
                .thenReturn(new CallCenterIdentityResolver.TranscriptionResult("Alguém", BigDecimal.ZERO));
        when(identityResolver.findFuzzyCandidateByName("Alguém")).thenReturn(Optional.empty());

        var node = nodeWith(null, null, "true");
        var graph = new FlowGraph(2, List.of(node), List.of(new FlowGraph.Edge("e1", "n1", "n2")));

        var edge = handler.handle(graph, node, context);

        assertThat(edge).isPresent();
        verify(identityResolver).logResolution("voice", "unresolved", BigDecimal.ZERO);
        verify(interactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("sem aresta de saída encerra o canal, mesmo padrão de coletar_texto")
    void withoutOutgoingEdge_callsEnd() throws Exception {
        when(driver.recordResponse(Duration.ofSeconds(15)))
                .thenReturn(ChannelDriver.RecordResult.recorded(recordedAudioPath));
        when(identityResolver.transcribe(any())).thenReturn(new CallCenterIdentityResolver.TranscriptionResult("x", BigDecimal.ZERO));
        var node = nodeWith("v", null, null);
        var graph = new FlowGraph(2, List.of(node), List.of());

        var edge = handler.handle(graph, node, context);

        assertThat(edge).isEmpty();
        verify(driver).end();
    }
}
