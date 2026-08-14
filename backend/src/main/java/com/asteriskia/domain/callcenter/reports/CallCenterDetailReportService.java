package com.asteriskia.domain.callcenter.reports;

import com.asteriskia.domain.callcenter.chat.CcChatSession;
import com.asteriskia.domain.callcenter.chat.CcChatSessionRepository;
import com.asteriskia.domain.callcenter.flow.engine.CcFlowExecution;
import com.asteriskia.domain.callcenter.flow.engine.CcFlowExecutionRepository;
import com.asteriskia.domain.callcenter.flow.engine.CcFlowExecutionStep;
import com.asteriskia.domain.callcenter.flow.engine.CcFlowExecutionStepRepository;
import com.asteriskia.domain.callcenter.flow.engine.FlowGraph;
import com.asteriskia.domain.callcenter.interaction.CcInteraction;
import com.asteriskia.domain.callcenter.interaction.CcInteractionRepository;
import com.asteriskia.domain.callcenter.interaction.CcInteractionSpecifications;
import com.asteriskia.domain.callcenter.recording.CcRecording;
import com.asteriskia.domain.callcenter.recording.CcRecordingRepository;
import com.asteriskia.domain.insights.CallAudioFile;
import com.asteriskia.domain.insights.CallAudioFileRepository;
import com.asteriskia.domain.insights.CallInsight;
import com.asteriskia.domain.insights.CallInsightFinding;
import com.asteriskia.domain.insights.CallInsightFindingRepository;
import com.asteriskia.domain.insights.CallInsightRepository;
import com.asteriskia.domain.insights.CallTranscriptSegmentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CallCenterDetailReportService — relatório analítico de chamada e de chat, linha a linha (Fase
 * 9c do plano omnicanal Parte III), distinto dos agregados de fila/agente (9a/9b, mesmo pacote).
 * Não duplica nenhum pipeline já existente: reusa a busca full-text de transcrição de voz
 * (Fase 8/V35), o link de áudio já persistido (Fase 3/8) e o traço de execução de fluxo (Fase
 * 5b) só como leitura — nunca decide roteamento nem reprocessa nada.
 *
 * <p>GAP CONHECIDO (mesmo padrão já aceito no Insights do Call Center, Fase 8): nenhum filtro de
 * BU é aplicado aqui, embora {@code CcInteraction}/{@code CcRecording}/{@code CcChatSession}
 * tenham campo {@code businessUnit} — um usuário com {@code PERM_READ_callcenter.reports} vê
 * chamadas/chats de todas as BUs, não só a sua. Resolver exigiria estender
 * {@code CcInteractionSpecifications} (e o filtro em memória de {@link #searchChats}) com o
 * mesmo padrão de {@code BusinessUnitContext} usado em outros pontos do domínio; fora do escopo
 * desta fase.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallCenterDetailReportService {

    private static final Pattern OPT_HANDLE_PATTERN = Pattern.compile("^opt-(.+)$");

    private final CcInteractionRepository interactionRepository;
    private final CcRecordingRepository recordingRepository;
    private final CallAudioFileRepository audioFileRepository;
    private final CallInsightRepository insightRepository;
    private final CallInsightFindingRepository findingRepository;
    private final CallTranscriptSegmentRepository transcriptSegmentRepository;
    private final CcFlowExecutionRepository flowExecutionRepository;
    private final CcFlowExecutionStepRepository flowExecutionStepRepository;
    private final CcChatSessionRepository chatSessionRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public Page<CallReportRow> searchCalls(CallReportFilter filter, Pageable pageable) {
        Specification<CcInteraction> spec = buildInteractionSpecification(filter);
        Page<CcInteraction> interactions = interactionRepository.findAll(spec, pageable);
        // Um único cache de grafo por página (não por linha) — várias interações da mesma página
        // costumam compartilhar a mesma versão de fluxo publicada, então reparsear o JSON do
        // grafo a cada linha seria trabalho redundante.
        Map<Long, FlowGraph> graphCache = new HashMap<>();
        return interactions.map(interaction -> toRow(interaction, graphCache));
    }

    @Transactional(readOnly = true)
    public Page<ChatReportRow> searchChats(ChatReportFilter filter, Pageable pageable) {
        // Volume de chat ainda é baixo nesta fase do projeto (Fase 7a/7b recém-entregues) —
        // filtro em memória sobre a listagem paginada é suficiente; se o volume crescer,
        // migrar para Specification/JpaSpecificationExecutor como o relatório de chamada.
        List<CcChatSession> all = chatSessionRepository.findAll();
        List<CcChatSession> filtered = all.stream().filter(s -> matchesChatFilter(s, filter)).toList();
        int from = Math.min((int) pageable.getOffset(), filtered.size());
        int to = Math.min(from + pageable.getPageSize(), filtered.size());
        List<ChatReportRow> pageContent = filtered.subList(from, to).stream().map(this::toRow).toList();
        return new org.springframework.data.domain.PageImpl<>(pageContent, pageable, filtered.size());
    }

    private boolean matchesChatFilter(CcChatSession session, ChatReportFilter filter) {
        if (filter.from() != null && session.getStartedAt().isBefore(filter.from())) return false;
        if (filter.to() != null && session.getStartedAt().isAfter(filter.to())) return false;
        if (filter.queueId() != null
                && (session.getQueue() == null || !filter.queueId().equals(session.getQueue().getId()))) return false;
        if (filter.agentId() != null
                && (session.getAssignedAgent() == null
                        || !filter.agentId().equals(session.getAssignedAgent().getId()))) return false;
        return true;
    }

    private ChatReportRow toRow(CcChatSession session) {
        return new ChatReportRow(
                session.getId(),
                session.getStartedAt(),
                session.getClaimedAt(),
                session.getClosedAt(),
                session.getCustomerRef(),
                session.getCustomerName(),
                session.getQueue() != null
                        ? (session.getQueue().getDisplayName() != null
                                ? session.getQueue().getDisplayName()
                                : session.getQueue().getName())
                        : null,
                session.getAssignedAgent() != null ? session.getAssignedAgent().getName() : null,
                session.getDisposition() != null ? session.getDisposition().getLabel() : null,
                session.getTranscriptPath());
    }

    private Specification<CcInteraction> buildInteractionSpecification(CallReportFilter filter) {
        Specification<CcInteraction> spec = Specification.where(null);
        if (filter.from() != null) {
            spec = spec.and(CcInteractionSpecifications.queuedAtFrom(filter.from()));
        }
        if (filter.to() != null) {
            spec = spec.and(CcInteractionSpecifications.queuedAtTo(filter.to()));
        }
        if (filter.queueId() != null) {
            spec = spec.and(CcInteractionSpecifications.queueIdEquals(filter.queueId()));
        }
        if (filter.agentId() != null) {
            spec = spec.and(CcInteractionSpecifications.agentIdEquals(filter.agentId()));
        }
        if (filter.direction() != null) {
            spec = spec.and(CcInteractionSpecifications.directionEquals(filter.direction()));
        }
        if (filter.npsMin() != null) {
            spec = spec.and(CcInteractionSpecifications.npsScoreFrom(filter.npsMin()));
        }
        if (filter.npsMax() != null) {
            spec = spec.and(CcInteractionSpecifications.npsScoreTo(filter.npsMax()));
        }
        if (filter.waitMinSeconds() != null || filter.waitMaxSeconds() != null) {
            long min = filter.waitMinSeconds() != null ? filter.waitMinSeconds() : 0L;
            long max = filter.waitMaxSeconds() != null ? filter.waitMaxSeconds() : Long.MAX_VALUE / 2;
            List<Long> ids = interactionRepository.findIdsByWaitSecondsBetween(min, max);
            spec = spec.and(CcInteractionSpecifications.idIn(ids));
        }
        if (filter.chosenOptionDigit() != null && !filter.chosenOptionDigit().isBlank()) {
            List<Long> ids = findInteractionIdsByChosenDigit(filter.chosenOptionDigit().trim());
            spec = spec.and(CcInteractionSpecifications.idIn(ids));
        }
        if (filter.transcriptText() != null && !filter.transcriptText().isBlank()) {
            List<Long> ids = findInteractionIdsByTranscriptText(filter.transcriptText().trim());
            spec = spec.and(CcInteractionSpecifications.idIn(ids));
        }
        return spec;
    }

    /** call_audio_files → cc_recordings → cc_interactions, na direção inversa do vínculo real
     * (CallAudioFile.ccRecordingId → CcRecording.interactionId) — mesma cadeia usada na
     * enriquecimento de linha, só que partindo do resultado da busca full-text (Fase 8/V35). */
    private List<Long> findInteractionIdsByTranscriptText(String text) {
        List<Long> audioFileIds = transcriptSegmentRepository.findAudioFileIdsByTextSearch(text);
        if (audioFileIds.isEmpty()) {
            return List.of();
        }
        return audioFileRepository.findAllById(audioFileIds).stream()
                .map(CallAudioFile::getCcRecordingId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .map(recordingRepository::findById)
                .flatMap(Optional::stream)
                .map(CcRecording::getInteractionId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    /** Best-effort: resolve o dígito real via {@code sourceHandle} da aresta no grafo da versão
     * (nunca por heurística sobre o id da aresta) — grafo inválido/versão sem grafo não derruba
     * o relatório, só deixa aquela execução de fora do filtro. Varre TODOS os passos de menu do
     * sistema sem paginação no banco — mesma decisão deliberada de baixo volume do relatório de
     * chat ({@link #searchChats}); revisitar (índice dedicado ou paginação) quando o Flow Builder
     * tiver volume real de produção. */
    private List<Long> findInteractionIdsByChosenDigit(String digit) {
        List<CcFlowExecutionStep> steps = flowExecutionStepRepository.findByNodeTypeAndTakenEdgeIsNotNull("menu_opcoes");
        Map<Long, FlowGraph> graphCache = new HashMap<>();
        return steps.stream()
                .map(step -> {
                    CcFlowExecution execution = step.getExecution();
                    if (execution == null || execution.getInteractionId() == null) {
                        return null;
                    }
                    String resolvedDigit = resolveChosenDigit(execution, step, graphCache);
                    return digit.equals(resolvedDigit) ? execution.getInteractionId() : null;
                })
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    private CallReportRow toRow(CcInteraction interaction, Map<Long, FlowGraph> graphCache) {
        Long waitSeconds = interaction.getAnsweredAt() != null
                ? Duration.between(interaction.getQueuedAt(), interaction.getAnsweredAt()).getSeconds()
                : null;

        CcRecording recording = recordingRepository.findByInteractionId(interaction.getId()).orElse(null);
        CallAudioFile audioFile = recording != null
                ? audioFileRepository.findByCcRecordingId(recording.getId()).orElse(null)
                : null;
        CallInsight insight = audioFile != null
                ? insightRepository.findByAudioFileId(audioFile.getId()).orElse(null)
                : null;
        Map<String, Long> findingsByTipo = audioFile != null
                ? findingRepository.findByAudioFileIdOrderByIdAsc(audioFile.getId()).stream()
                        .collect(Collectors.groupingBy(CallInsightFinding::getTipo, Collectors.counting()))
                : Map.of();

        CcFlowExecution execution = flowExecutionRepository.findByInteractionId(interaction.getId()).orElse(null);
        String flowName = execution != null && execution.getFlow() != null ? execution.getFlow().getName() : null;
        String chosenDigit = null;
        String chosenLabel = null;
        if (execution != null) {
            var menuStep = flowExecutionStepRepository.findByExecutionIdOrderByEnteredAtAsc(execution.getId()).stream()
                    .filter(s -> "menu_opcoes".equals(s.getNodeType()) && s.getTakenEdge() != null)
                    .findFirst();
            if (menuStep.isPresent()) {
                chosenDigit = resolveChosenDigit(execution, menuStep.get(), graphCache);
                chosenLabel = resolveChosenLabel(execution, menuStep.get(), chosenDigit, graphCache);
            }
        }

        return new CallReportRow(
                interaction.getId(),
                interaction.getQueuedAt(),
                interaction.getAnsweredAt(),
                interaction.getEndedAt(),
                interaction.getDirection() != null ? interaction.getDirection().name() : null,
                interaction.getAni(),
                interaction.getQueue() != null
                        ? (interaction.getQueue().getDisplayName() != null
                                ? interaction.getQueue().getDisplayName()
                                : interaction.getQueue().getName())
                        : null,
                interaction.getAgent() != null ? interaction.getAgent().getName() : null,
                waitSeconds,
                interaction.getNpsScore(),
                flowName,
                chosenDigit,
                chosenLabel,
                audioFile != null ? audioFile.getId() : null,
                insight != null ? insight.getCategoriaAssunto() : null,
                insight != null ? insight.getSentimentoGeral() : null,
                insight != null ? insight.getCriticidade() : null,
                findingsByTipo);
    }

    private String resolveChosenDigit(CcFlowExecution execution, CcFlowExecutionStep step, Map<Long, FlowGraph> cache) {
        FlowGraph graph = loadGraph(execution, cache);
        if (graph == null) {
            return null;
        }
        return graph.edges().stream()
                .filter(e -> e.id().equals(step.getTakenEdge()))
                .findFirst()
                .map(FlowGraph.Edge::sourceHandle)
                .map(OPT_HANDLE_PATTERN::matcher)
                .filter(java.util.regex.Matcher::matches)
                .map(m -> m.group(1))
                .orElse(null);
    }

    private String resolveChosenLabel(
            CcFlowExecution execution, CcFlowExecutionStep step, String digit, Map<Long, FlowGraph> cache) {
        if (digit == null) {
            return null;
        }
        FlowGraph graph = loadGraph(execution, cache);
        if (graph == null) {
            return null;
        }
        return graph.findNode(step.getNodeId())
                .map(node -> node.data().property("opcoesMenu"))
                .flatMap(json -> parseOpcaoLabel(json, digit))
                .orElse(null);
    }

    private Optional<String> parseOpcaoLabel(String opcoesMenuJson, String digit) {
        if (opcoesMenuJson == null || opcoesMenuJson.isBlank()) {
            return Optional.empty();
        }
        try {
            var arrayNode = objectMapper.readTree(opcoesMenuJson);
            if (!arrayNode.isArray()) {
                return Optional.empty();
            }
            for (var item : arrayNode) {
                if (digit.equals(item.path("digito").asText(null))) {
                    return Optional.ofNullable(item.path("rotulo").asText(null));
                }
            }
        } catch (Exception e) {
            log.debug("Não foi possível resolver rótulo da opção escolhida: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private FlowGraph loadGraph(CcFlowExecution execution, Map<Long, FlowGraph> cache) {
        var version = execution.getFlowVersion();
        if (version == null) {
            return null;
        }
        return cache.computeIfAbsent(version.getId(), id -> {
            try {
                return FlowGraph.parse(objectMapper, version.getGraph());
            } catch (Exception e) {
                log.debug("Grafo inválido para versão de fluxo id={}: {}", id, e.getMessage());
                return null;
            }
        });
    }
}
