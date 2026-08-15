package com.asteriskia.domain.callcenter.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.chat.CcChatSession;
import com.asteriskia.domain.callcenter.chat.CcChatSessionRepository;
import com.asteriskia.domain.callcenter.flow.CcFlow;
import com.asteriskia.domain.callcenter.flow.CcFlowVersion;
import com.asteriskia.domain.callcenter.flow.engine.CcFlowExecution;
import com.asteriskia.domain.callcenter.flow.engine.CcFlowExecutionRepository;
import com.asteriskia.domain.callcenter.flow.engine.CcFlowExecutionStep;
import com.asteriskia.domain.callcenter.flow.engine.CcFlowExecutionStepRepository;
import com.asteriskia.domain.callcenter.interaction.CcInteraction;
import com.asteriskia.domain.callcenter.interaction.CcInteractionRepository;
import com.asteriskia.domain.callcenter.interaction.Direction;
import com.asteriskia.domain.callcenter.recording.CcRecording;
import com.asteriskia.domain.callcenter.recording.CcRecordingRepository;
import com.asteriskia.domain.masterdata.BusinessUnit;
import com.asteriskia.domain.insights.CallAudioFile;
import com.asteriskia.domain.insights.CallAudioFileRepository;
import com.asteriskia.domain.insights.CallInsight;
import com.asteriskia.domain.insights.CallInsightFinding;
import com.asteriskia.domain.insights.CallInsightFindingRepository;
import com.asteriskia.domain.insights.CallInsightRepository;
import com.asteriskia.domain.insights.CallTranscriptSegmentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

/**
 * Cobre o enriquecimento e os filtros do relatório analítico de chamada/chat (Fase 9c) — não
 * testa o controller (mesma convenção já estabelecida no domínio callcenter nesta sessão).
 */
@ExtendWith(MockitoExtension.class)
class CallCenterDetailReportServiceTest {

    @Mock private CcInteractionRepository interactionRepository;
    @Mock private CcRecordingRepository recordingRepository;
    @Mock private CallAudioFileRepository audioFileRepository;
    @Mock private CallInsightRepository insightRepository;
    @Mock private CallInsightFindingRepository findingRepository;
    @Mock private CallTranscriptSegmentRepository transcriptSegmentRepository;
    @Mock private CcFlowExecutionRepository flowExecutionRepository;
    @Mock private CcFlowExecutionStepRepository flowExecutionStepRepository;
    @Mock private CcChatSessionRepository chatSessionRepository;

    private CallCenterDetailReportService service;

    private CcQueue queue;
    private CcAgent agent;
    private CcInteraction interaction;

    @BeforeEach
    void setUp() {
        service = new CallCenterDetailReportService(
                interactionRepository, recordingRepository, audioFileRepository, insightRepository,
                findingRepository, transcriptSegmentRepository, flowExecutionRepository,
                flowExecutionStepRepository, chatSessionRepository, new ObjectMapper());

        queue = CcQueue.builder().id(10L).name("5001").displayName("Suporte").build();
        agent = CcAgent.builder().id(20L).name("Kaio").build();
        interaction = CcInteraction.builder()
                .id(1L)
                .queue(queue)
                .agent(agent)
                .direction(Direction.INBOUND)
                .ani("11999990000")
                .queuedAt(LocalDateTime.of(2026, 8, 14, 10, 0))
                .answeredAt(LocalDateTime.of(2026, 8, 14, 10, 0, 30))
                .endedAt(LocalDateTime.of(2026, 8, 14, 10, 5))
                .npsScore(new java.math.BigDecimal("9"))
                .build();
    }

    private Page<CcInteraction> onePage(CcInteraction i) {
        return new PageImpl<>(List.of(i));
    }

    @Test
    @DisplayName("linha básica sem gravação nem fluxo associado vem com campos de enriquecimento nulos")
    void searchCalls_noRecordingNoFlow_rowHasNullEnrichmentFields() {
        when(interactionRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(onePage(interaction));
        when(recordingRepository.findByInteractionId(1L)).thenReturn(Optional.empty());
        when(flowExecutionRepository.findByInteractionId(1L)).thenReturn(Optional.empty());

        CallReportFilter filter = new CallReportFilter(null, null, null, null, null, null, null, null, null, null, null);
        Page<CallReportRow> result = service.searchCalls(filter, PageRequest.of(0, 20), null);

        CallReportRow row = result.getContent().get(0);
        assertThat(row.interactionId()).isEqualTo(1L);
        assertThat(row.queueName()).isEqualTo("Suporte");
        assertThat(row.agentName()).isEqualTo("Kaio");
        assertThat(row.waitSeconds()).isEqualTo(30L);
        assertThat(row.npsScore()).isEqualByComparingTo("9");
        assertThat(row.audioFileId()).isNull();
        assertThat(row.flowName()).isNull();
        assertThat(row.chosenOptionDigit()).isNull();
        assertThat(row.findingsByTipo()).isEmpty();
    }

    @Test
    @DisplayName("chamada não atendida (answeredAt nulo) não calcula tempo de espera")
    void searchCalls_notAnswered_waitSecondsIsNull() {
        interaction.setAnsweredAt(null);
        when(interactionRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(onePage(interaction));
        when(recordingRepository.findByInteractionId(1L)).thenReturn(Optional.empty());
        when(flowExecutionRepository.findByInteractionId(1L)).thenReturn(Optional.empty());

        CallReportFilter filter = new CallReportFilter(null, null, null, null, null, null, null, null, null, null, null);
        Page<CallReportRow> result = service.searchCalls(filter, PageRequest.of(0, 20), null);

        assertThat(result.getContent().get(0).waitSeconds()).isNull();
    }

    @Test
    @DisplayName("com gravação e insight, enriquece áudio/categoria/achados na linha")
    void searchCalls_withRecordingAndInsight_enrichesAudioAndFindings() {
        when(interactionRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(onePage(interaction));
        CcRecording recording = CcRecording.builder().id(100L).interactionId(1L).build();
        when(recordingRepository.findByInteractionId(1L)).thenReturn(Optional.of(recording));
        CallAudioFile audioFile = CallAudioFile.builder().id(200L).ccRecordingId(100L).build();
        when(audioFileRepository.findByCcRecordingId(100L)).thenReturn(Optional.of(audioFile));
        CallInsight insight = CallInsight.builder()
                .id(300L).audioFileId(200L).categoriaAssunto("Fatura").sentimentoGeral("neutro")
                .criticidade("media").insightsJson("{}").build();
        when(insightRepository.findByAudioFileId(200L)).thenReturn(Optional.of(insight));
        when(findingRepository.findByAudioFileIdOrderByIdAsc(200L)).thenReturn(List.of(
                CallInsightFinding.builder().id(1L).audioFileId(200L).tipo("melhoria").descricao("x").build(),
                CallInsightFinding.builder().id(2L).audioFileId(200L).tipo("melhoria").descricao("y").build(),
                CallInsightFinding.builder().id(3L).audioFileId(200L).tipo("falha").descricao("z").build()));
        when(flowExecutionRepository.findByInteractionId(1L)).thenReturn(Optional.empty());

        CallReportFilter filter = new CallReportFilter(null, null, null, null, null, null, null, null, null, null, null);
        CallReportRow row = service.searchCalls(filter, PageRequest.of(0, 20), null).getContent().get(0);

        assertThat(row.audioFileId()).isEqualTo(200L);
        assertThat(row.categoriaAssunto()).isEqualTo("Fatura");
        assertThat(row.criticidade()).isEqualTo("media");
        assertThat(row.findingsByTipo()).containsEntry("melhoria", 2L).containsEntry("falha", 1L);
    }

    @Test
    @DisplayName("resolve dígito e rótulo da opção escolhida via sourceHandle da aresta, nunca por heurística sobre o id")
    void searchCalls_withMenuStep_resolvesChosenDigitAndLabelFromSourceHandle() throws Exception {
        when(interactionRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(onePage(interaction));
        when(recordingRepository.findByInteractionId(1L)).thenReturn(Optional.empty());

        CcFlow flow = CcFlow.builder().id(5L).name("URA Vendas").build();
        String graphJson = """
                {"schemaVersion":2,"nodes":[
                  {"id":"n1","type":"generic","data":{"nodeType":"menu_opcoes","label":"Menu",
                    "properties":{"opcoesMenu":"[{\\"digito\\":\\"3\\",\\"rotulo\\":\\"Financeiro\\"}]"}}}
                ],"edges":[
                  {"id":"e-random-id-1","source":"n1","target":"n2","sourceHandle":"opt-3"}
                ]}""";
        CcFlowVersion version = CcFlowVersion.builder().id(50L).graph(graphJson).build();
        CcFlowExecution execution = CcFlowExecution.builder()
                .id(60L).flow(flow).flowVersion(version).interactionId(1L).channelId("ch1").build();
        when(flowExecutionRepository.findByInteractionId(1L)).thenReturn(Optional.of(execution));

        CcFlowExecutionStep step = CcFlowExecutionStep.builder()
                .id(70L).execution(execution).nodeId("n1").nodeType("menu_opcoes").takenEdge("e-random-id-1").build();
        when(flowExecutionStepRepository.findByExecutionIdOrderByEnteredAtAsc(60L)).thenReturn(List.of(step));

        CallReportFilter filter = new CallReportFilter(null, null, null, null, null, null, null, null, null, null, null);
        CallReportRow row = service.searchCalls(filter, PageRequest.of(0, 20), null).getContent().get(0);

        assertThat(row.flowName()).isEqualTo("URA Vendas");
        assertThat(row.chosenOptionDigit()).isEqualTo("3");
        assertThat(row.chosenOptionLabel()).isEqualTo("Financeiro");
    }

    @Test
    @DisplayName("grafo inválido na versão do fluxo não derruba o relatório, só deixa opção escolhida nula")
    void searchCalls_invalidGraphJson_neverThrowsAndOptionStaysNull() {
        when(interactionRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(onePage(interaction));
        when(recordingRepository.findByInteractionId(1L)).thenReturn(Optional.empty());

        CcFlow flow = CcFlow.builder().id(5L).name("URA Vendas").build();
        CcFlowVersion version = CcFlowVersion.builder().id(50L).graph("{ isso não é json válido").build();
        CcFlowExecution execution = CcFlowExecution.builder()
                .id(60L).flow(flow).flowVersion(version).interactionId(1L).channelId("ch1").build();
        when(flowExecutionRepository.findByInteractionId(1L)).thenReturn(Optional.of(execution));
        CcFlowExecutionStep step = CcFlowExecutionStep.builder()
                .id(70L).execution(execution).nodeId("n1").nodeType("menu_opcoes").takenEdge("e1").build();
        when(flowExecutionStepRepository.findByExecutionIdOrderByEnteredAtAsc(60L)).thenReturn(List.of(step));

        CallReportFilter filter = new CallReportFilter(null, null, null, null, null, null, null, null, null, null, null);
        CallReportRow row = service.searchCalls(filter, PageRequest.of(0, 20), null).getContent().get(0);

        assertThat(row.flowName()).isEqualTo("URA Vendas");
        assertThat(row.chosenOptionDigit()).isNull();
        assertThat(row.chosenOptionLabel()).isNull();
    }

    @Test
    @DisplayName("filtro de tempo de espera combina os ids retornados pela query nativa via especificação")
    void searchCalls_withWaitTimeFilter_queriesIdsAndAppliesSpecification() {
        when(interactionRepository.findIdsByWaitSecondsBetween(10L, 60L)).thenReturn(List.of(1L, 2L));
        when(interactionRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(onePage(interaction));
        when(recordingRepository.findByInteractionId(1L)).thenReturn(Optional.empty());
        when(flowExecutionRepository.findByInteractionId(1L)).thenReturn(Optional.empty());

        CallReportFilter filter = new CallReportFilter(null, null, null, null, null, null, null, 10L, 60L, null, null);
        service.searchCalls(filter, PageRequest.of(0, 20), null);

        org.mockito.Mockito.verify(interactionRepository).findIdsByWaitSecondsBetween(10L, 60L);
    }

    @Test
    @DisplayName("filtro de opção escolhida só casa interações cujo sourceHandle resolvido bate com o dígito pedido")
    void searchCalls_withChosenOptionFilter_matchesOnlyResolvedDigit() throws Exception {
        CcFlow flow = CcFlow.builder().id(5L).name("URA Vendas").build();
        String graphJson = """
                {"schemaVersion":2,"nodes":[
                  {"id":"n1","type":"generic","data":{"nodeType":"menu_opcoes","label":"Menu","properties":{}}}
                ],"edges":[
                  {"id":"e-a","source":"n1","target":"n2","sourceHandle":"opt-3"},
                  {"id":"e-b","source":"n1","target":"n3","sourceHandle":"opt-9"}
                ]}""";
        CcFlowVersion version = CcFlowVersion.builder().id(50L).graph(graphJson).build();
        CcFlowExecution executionMatching = CcFlowExecution.builder()
                .id(60L).flow(flow).flowVersion(version).interactionId(1L).channelId("ch1").build();
        CcFlowExecution executionOther = CcFlowExecution.builder()
                .id(61L).flow(flow).flowVersion(version).interactionId(2L).channelId("ch2").build();
        CcFlowExecutionStep stepMatching = CcFlowExecutionStep.builder()
                .id(70L).execution(executionMatching).nodeId("n1").nodeType("menu_opcoes").takenEdge("e-a").build();
        CcFlowExecutionStep stepOther = CcFlowExecutionStep.builder()
                .id(71L).execution(executionOther).nodeId("n1").nodeType("menu_opcoes").takenEdge("e-b").build();
        when(flowExecutionStepRepository.findByNodeTypeAndTakenEdgeIsNotNull("menu_opcoes"))
                .thenReturn(List.of(stepMatching, stepOther));
        when(interactionRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(onePage(interaction));
        when(recordingRepository.findByInteractionId(1L)).thenReturn(Optional.empty());
        when(flowExecutionRepository.findByInteractionId(1L)).thenReturn(Optional.empty());

        CallReportFilter filter = new CallReportFilter(null, null, null, null, null, null, null, null, null, "3", null);
        service.searchCalls(filter, PageRequest.of(0, 20), null);

        org.mockito.Mockito.verify(flowExecutionStepRepository).findByNodeTypeAndTakenEdgeIsNotNull("menu_opcoes");
    }

    @Test
    @DisplayName("filtro de trecho de transcrição resolve a cadeia call_audio_files→cc_recordings→cc_interactions")
    void searchCalls_withTranscriptTextFilter_resolvesInteractionIdsThroughChain() {
        when(transcriptSegmentRepository.findAudioFileIdsByTextSearch("cobrança indevida")).thenReturn(List.of(200L));
        CallAudioFile audioFile = CallAudioFile.builder().id(200L).ccRecordingId(100L).build();
        when(audioFileRepository.findAllById(List.of(200L))).thenReturn(List.of(audioFile));
        CcRecording recording = CcRecording.builder().id(100L).interactionId(1L).build();
        when(recordingRepository.findById(100L)).thenReturn(Optional.of(recording));
        when(interactionRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(onePage(interaction));
        when(recordingRepository.findByInteractionId(1L)).thenReturn(Optional.empty());
        when(flowExecutionRepository.findByInteractionId(1L)).thenReturn(Optional.empty());

        CallReportFilter filter =
                new CallReportFilter(null, null, null, null, null, null, null, null, null, null, "cobrança indevida");
        Page<CallReportRow> result = service.searchCalls(filter, PageRequest.of(0, 20), null);

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    @DisplayName("relatório de chat filtra por fila e agente, sem exigir NPS/transcrição")
    void searchChats_filtersByQueueAndAgent() {
        CcChatSession matching = CcChatSession.builder()
                .id(1L).queue(queue).assignedAgent(agent)
                .customerRef("cust-1").startedAt(LocalDateTime.of(2026, 8, 14, 9, 0))
                .status("closed").build();
        CcChatSession otherQueue = CcChatSession.builder()
                .id(2L).queue(CcQueue.builder().id(99L).name("outra").build()).assignedAgent(agent)
                .customerRef("cust-2").startedAt(LocalDateTime.of(2026, 8, 14, 9, 0))
                .status("closed").build();
        when(chatSessionRepository.findAll()).thenReturn(List.of(matching, otherQueue));

        ChatReportFilter filter = new ChatReportFilter(null, null, 10L, null);
        Page<ChatReportRow> result = service.searchChats(filter, PageRequest.of(0, 20), null);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).sessionId()).isEqualTo(1L);
        assertThat(result.getContent().get(0).queueName()).isEqualTo("Suporte");
    }

    // --- Escopo por BU no relatório 9c (fechado em 2026-08-15) ---

    @Test
    @DisplayName("searchCalls: ADMIN (businessUnitIds nulo) nunca adiciona restrição de BU à Specification")
    void searchCalls_admin_neverAddsBusinessUnitRestriction() {
        when(interactionRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(onePage(interaction));
        when(recordingRepository.findByInteractionId(1L)).thenReturn(Optional.empty());
        when(flowExecutionRepository.findByInteractionId(1L)).thenReturn(Optional.empty());
        ArgumentCaptor<Specification<CcInteraction>> specCaptor = ArgumentCaptor.forClass(Specification.class);

        CallReportFilter filter = new CallReportFilter(null, null, null, null, null, null, null, null, null, null, null);
        service.searchCalls(filter, PageRequest.of(0, 20), null);

        org.mockito.Mockito.verify(interactionRepository).findAll(specCaptor.capture(), any(PageRequest.class));
        assertThat(invokesBusinessUnitPredicate(specCaptor.getValue())).isFalse();
    }

    @Test
    @DisplayName("searchCalls: usuário restrito a uma BU adiciona a restrição de BU à Specification")
    void searchCalls_restrictedUser_addsBusinessUnitRestriction() {
        when(interactionRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(onePage(interaction));
        when(recordingRepository.findByInteractionId(1L)).thenReturn(Optional.empty());
        when(flowExecutionRepository.findByInteractionId(1L)).thenReturn(Optional.empty());
        ArgumentCaptor<Specification<CcInteraction>> specCaptor = ArgumentCaptor.forClass(Specification.class);

        CallReportFilter filter = new CallReportFilter(null, null, null, null, null, null, null, null, null, null, null);
        service.searchCalls(filter, PageRequest.of(0, 20), Set.of(5));

        org.mockito.Mockito.verify(interactionRepository).findAll(specCaptor.capture(), any(PageRequest.class));
        assertThat(invokesBusinessUnitPredicate(specCaptor.getValue())).isTrue();
    }

    /** Invoca a Specification capturada com Root/CriteriaBuilder mockados e confirma se ela
     * chega a acessar {@code root.get("businessUnit")} — prova indireta de que o predicado de
     * BU foi (ou não foi) composto, sem precisar de um EntityManager real. */
    @SuppressWarnings("unchecked")
    private boolean invokesBusinessUnitPredicate(Specification<CcInteraction> spec) {
        Root<CcInteraction> root = org.mockito.Mockito.mock(Root.class);
        CriteriaQuery<?> query = org.mockito.Mockito.mock(CriteriaQuery.class);
        CriteriaBuilder cb = org.mockito.Mockito.mock(CriteriaBuilder.class);
        Path<Object> businessUnitPath = org.mockito.Mockito.mock(Path.class);
        org.mockito.Mockito.lenient().when(cb.and(org.mockito.ArgumentMatchers.any(Predicate.class), org.mockito.ArgumentMatchers.any(Predicate.class)))
                .thenReturn(org.mockito.Mockito.mock(Predicate.class));
        org.mockito.Mockito.lenient().when(cb.or(org.mockito.ArgumentMatchers.any(Predicate[].class)))
                .thenReturn(org.mockito.Mockito.mock(Predicate.class));
        org.mockito.Mockito.lenient().when(cb.conjunction()).thenReturn(org.mockito.Mockito.mock(Predicate.class));
        org.mockito.Mockito.lenient().when(cb.isNull(org.mockito.ArgumentMatchers.any())).thenReturn(org.mockito.Mockito.mock(Predicate.class));
        org.mockito.Mockito.lenient().when(root.get("businessUnit")).thenReturn(businessUnitPath);
        org.mockito.Mockito.lenient().when(businessUnitPath.get("id")).thenReturn(businessUnitPath);
        org.mockito.Mockito.lenient().when(businessUnitPath.in(org.mockito.ArgumentMatchers.any(java.util.Collection.class)))
                .thenReturn(org.mockito.Mockito.mock(Predicate.class));
        try {
            spec.toPredicate(root, query, cb);
            org.mockito.Mockito.verify(root, org.mockito.Mockito.atLeastOnce()).get("businessUnit");
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    @Test
    @DisplayName("searchChats: ADMIN (businessUnitIds nulo) vê sessões de qualquer BU")
    void searchChats_admin_seesAllBusinessUnits() {
        BusinessUnit bu7 = BusinessUnit.builder().id(7).build();
        CcChatSession sessionBu7 = CcChatSession.builder()
                .id(1L).queue(queue).businessUnit(bu7)
                .customerRef("cust-1").startedAt(LocalDateTime.of(2026, 8, 14, 9, 0)).build();
        when(chatSessionRepository.findAll()).thenReturn(List.of(sessionBu7));

        ChatReportFilter filter = new ChatReportFilter(null, null, null, null);
        Page<ChatReportRow> result = service.searchChats(filter, PageRequest.of(0, 20), null);

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    @DisplayName("searchChats: usuário restrito não vê sessão de outra BU")
    void searchChats_restrictedUser_excludesOtherBusinessUnit() {
        BusinessUnit bu7 = BusinessUnit.builder().id(7).build();
        CcChatSession sessionBu7 = CcChatSession.builder()
                .id(1L).queue(queue).businessUnit(bu7)
                .customerRef("cust-1").startedAt(LocalDateTime.of(2026, 8, 14, 9, 0)).build();
        when(chatSessionRepository.findAll()).thenReturn(List.of(sessionBu7));

        ChatReportFilter filter = new ChatReportFilter(null, null, null, null);
        Page<ChatReportRow> result = service.searchChats(filter, PageRequest.of(0, 20), Set.of(5));

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("searchChats: sessão sem BU atribuída é visível mesmo para usuário restrito (fail-open)")
    void searchChats_restrictedUser_sessionWithoutBusinessUnitFailsOpen() {
        CcChatSession sessionSemBu = CcChatSession.builder()
                .id(1L).queue(queue)
                .customerRef("cust-1").startedAt(LocalDateTime.of(2026, 8, 14, 9, 0)).build();
        when(chatSessionRepository.findAll()).thenReturn(List.of(sessionSemBu));

        ChatReportFilter filter = new ChatReportFilter(null, null, null, null);
        Page<ChatReportRow> result = service.searchChats(filter, PageRequest.of(0, 20), Set.of(5));

        assertThat(result.getContent()).hasSize(1);
    }
}
