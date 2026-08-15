package com.asteriskia.domain.insights;

import com.asteriskia.domain.callcenter.recording.CcRecordingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * InsightsQueryService — busca/detalhe/dashboard da tela Insights.
 *
 * Texto livre, frase exata, tom (cliente/atendente), categoria, criticidade e
 * tipo de achado vivem em call_transcript_segments/call_insights/
 * call_insight_findings, não em call_audio_files — cada
 * critério informado é resolvido antes para um Set de audioFileId, e a
 * interseção desses sets vira o filtro "id IN (...)" da Specification
 * principal (ver InsightsSpecifications). Nenhum critério informado =
 * nenhuma restrição de IDs.
 */
@Service
@RequiredArgsConstructor
public class InsightsQueryService {

    private final CallAudioFileRepository audioFileRepository;
    private final CallTranscriptSegmentRepository segmentRepository;
    private final CallInsightRepository insightRepository;
    private final CallInsightFindingRepository findingRepository;
    private final CallEvaluationRepository evaluationRepository;
    private final CallEvaluationItemRepository evaluationItemRepository;
    private final CallTransferEventRepository transferEventRepository;
    private final CcRecordingRepository ccRecordingRepository;

    public Page<InsightsListItem> search(InsightsFilter filter, Pageable pageable, boolean isAdmin) {
        return search(filter, pageable, isAdmin, "verint");
    }

    /** source parametrizado (Fase 8 do Call Center) — mesma busca, aplicada às gravações do
     * Call Center (source='callcenter') pela InsightsController correspondente daquele módulo. */
    public Page<InsightsListItem> search(InsightsFilter filter, Pageable pageable, boolean isAdmin, String source) {
        return search(filter, pageable, isAdmin, source, null);
    }

    /** {@code businessUnitIds} (fecha parte do gap de BU documentado em CLAUDE.md — Insights do
     * Call Center não filtrava por BU): {@code null} = sem restrição (ADMIN, ou o caminho Verint
     * que nunca teve conceito de BU); não-nulo restringe às BUs do usuário (fail-open pra
     * gravação sem BU atribuída, ver {@link InsightsSpecifications#restrictedToBusinessUnits}). */
    public Page<InsightsListItem> search(
            InsightsFilter filter, Pageable pageable, boolean isAdmin, String source, Set<Integer> businessUnitIds) {
        List<Long> restrictedToIds = resolveRestrictedIds(filter, isAdmin);

        Specification<CallAudioFile> spec = InsightsSpecifications.withFilters(filter, restrictedToIds, source);
        if (businessUnitIds != null) {
            spec = spec.and(InsightsSpecifications.restrictedToBusinessUnits(businessUnitIds));
        }
        Page<CallAudioFile> page = audioFileRepository.findAll(spec, pageable);

        List<Long> pageIds = page.getContent().stream().map(CallAudioFile::getId).toList();
        Map<Long, CallInsight> insightsByAudioFileId = insightRepository.findByAudioFileIdIn(pageIds).stream()
                .collect(Collectors.toMap(CallInsight::getAudioFileId, i -> i));
        Map<Long, CallEvaluation> evaluationsByAudioFileId = evaluationRepository.findByAudioFileIdIn(pageIds).stream()
                .collect(Collectors.toMap(CallEvaluation::getAudioFileId, e -> e));
        Map<Long, CallTransferEvent> lastTransferByAudioFileId = lastTransferEventByAudioFileId(pageIds);

        return page.map(audioFile ->
                InsightsListItem.from(audioFile, insightsByAudioFileId.get(audioFile.getId()),
                        evaluationsByAudioFileId.get(audioFile.getId()),
                        lastTransferByAudioFileId.get(audioFile.getId())));
    }

    public InsightsDetailResponse detail(Long id, boolean isAdmin) {
        return detail(id, isAdmin, null);
    }

    /** {@code businessUnitIds}: ver {@link #search(InsightsFilter, Pageable, boolean, String,
     * Set)}. Registro fora do escopo vira 404 (nunca 403 — mesma disciplina anti-IDOR já usada em
     * outros pontos do Call Center: não confirma nem a existência do id para quem não tem acesso). */
    public InsightsDetailResponse detail(Long id, boolean isAdmin, Set<Integer> businessUnitIds) {
        CallAudioFile audioFile = audioFileRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Chamada não encontrada: id=" + id));
        assertBusinessUnitAccessible(audioFile, businessUnitIds);
        List<CallTranscriptSegment> segments = segmentRepository.findByAudioFileIdOrderByStartMsAsc(id);
        CallInsight insight = insightRepository.findByAudioFileId(id).orElse(null);
        List<CallInsightFinding> findings = findingRepository.findByAudioFileIdOrderByIdAsc(id);
        CallEvaluation evaluation = evaluationRepository.findByAudioFileId(id).orElse(null);
        List<CallEvaluationItem> evaluationItems = evaluation != null
                ? evaluationItemRepository.findByEvaluationIdOrderByIdAsc(evaluation.getId())
                : List.of();
        List<CallTransferEventDto> transferEvents = transferEventRepository.findByAudioFileIdOrderByTransferOrderAsc(id)
                .stream().map(e -> CallTransferEventDto.from(e, isAdmin)).toList();
        return new InsightsDetailResponse(InsightsAudioFileDto.from(audioFile, isAdmin), segments, insight, findings,
                evaluation, evaluationItems, transferEvents);
    }

    /** Último evento de transferência (maior transferOrder) de cada chamada da página —
     * uma query pra todas as IDs, sem N+1. */
    private Map<Long, CallTransferEvent> lastTransferEventByAudioFileId(List<Long> audioFileIds) {
        if (audioFileIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, CallTransferEvent> result = new HashMap<>();
        for (CallTransferEvent event : transferEventRepository.findByAudioFileIdInOrderByAudioFileIdAscTransferOrderAsc(audioFileIds)) {
            result.put(event.getAudioFileId(), event); // sobrescreve com o de maior transferOrder (ordem ASC)
        }
        return result;
    }

    public CallAudioFile findAudioFileById(Long id) {
        return findAudioFileById(id, null);
    }

    /** {@code businessUnitIds}: ver {@link #search(InsightsFilter, Pageable, boolean, String,
     * Set)}. Usada pelo streaming de áudio do Call Center — nunca deixa um agente/supervisor
     * restrito a uma BU ouvir a gravação de outra BU só porque adivinhou o id. */
    public CallAudioFile findAudioFileById(Long id, Set<Integer> businessUnitIds) {
        CallAudioFile audioFile = audioFileRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Chamada não encontrada: id=" + id));
        assertBusinessUnitAccessible(audioFile, businessUnitIds);
        return audioFile;
    }

    /** {@code null} = sem restrição. Fail-open (mesmo padrão do resto do domínio, ver
     * {@code CallRecordService.inBusinessUnitScope}): gravação sem {@code ccRecordingId}, sem
     * {@code CcRecording} correspondente, ou cuja fila não tem BU atribuída fica visível a todos —
     * a BU é opcional no cadastro de fila, não obrigatória. */
    private void assertBusinessUnitAccessible(CallAudioFile audioFile, Set<Integer> businessUnitIds) {
        if (businessUnitIds == null || audioFile.getCcRecordingId() == null) {
            return;
        }
        var recording = ccRecordingRepository.findById(audioFile.getCcRecordingId()).orElse(null);
        if (recording == null || recording.getBusinessUnit() == null) {
            return;
        }
        if (!businessUnitIds.contains(recording.getBusinessUnit().getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Chamada não encontrada.");
        }
    }

    public InsightsDashboardSummary dashboard() {
        return dashboard("verint");
    }

    /** source parametrizado (Fase 8 do Call Center) — todos os agregados (criticidade,
     * categoria, achados, nota média, auto-fails) são filtrados por source via JOIN com
     * call_audio_files, mesmo padrão já usado pelo dashboard de Insights (verint). */
    public InsightsDashboardSummary dashboard(String source) {
        long total = audioFileRepository.countBySource(source);

        Map<String, Long> porCriticidade = new HashMap<>();
        for (Object[] row : insightRepository.countByCriticidade(source)) {
            porCriticidade.put((String) row[0], (Long) row[1]);
        }

        Map<String, Long> porCategoria = new HashMap<>();
        for (Object[] row : insightRepository.countByCategoria(source)) {
            porCategoria.put((String) row[0], (Long) row[1]);
        }

        Map<String, Long> achadosPorTipo = new HashMap<>();
        for (Object[] row : findingRepository.countByTipo(source)) {
            achadosPorTipo.put((String) row[0], (Long) row[1]);
        }

        Map<String, Double> mediaNotaPorAgente = new HashMap<>();
        for (Object[] row : evaluationRepository.averageNotaByAgent(source)) {
            java.math.BigDecimal media = (java.math.BigDecimal) row[1];
            mediaNotaPorAgente.put((String) row[0], media != null ? media.doubleValue() : null);
        }
        double mediaGeral = mediaNotaPorAgente.values().stream()
                .filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue).average().orElse(0.0);
        long agentesAbaixoMedia = mediaNotaPorAgente.values().stream()
                .filter(java.util.Objects::nonNull)
                .filter(m -> m < mediaGeral)
                .count();
        long autoFailsNoPeriodo = evaluationRepository.countFailed(source);

        return new InsightsDashboardSummary(total, porCriticidade, porCategoria, achadosPorTipo,
                mediaGeral, agentesAbaixoMedia, autoFailsNoPeriodo);
    }

    /** Aba "Processamento" — status/fila de cada arquivo descoberto em /opt/audio. Posição na
     * fila calculada só para status='pending' (FIFO por ordem de descoberta); demais status
     * ficam com queuePosition=null. */
    public Page<InsightProcessingItem> findProcessing(InsightsProcessingFilter filter, Pageable pageable) {
        return findProcessing(filter, pageable, "verint");
    }

    /** source parametrizado (Fase 8 do Call Center). */
    public Page<InsightProcessingItem> findProcessing(
            InsightsProcessingFilter filter, Pageable pageable, String source) {
        return findProcessing(filter, pageable, source, null);
    }

    /** {@code businessUnitIds} (2026-08-15, extensão do gap de BU fechado em {@code /calls}): só
     * usada por {@code source="callcenter"} — Insights (Verint) nunca teve conceito de BU e
     * continua sem restrição. {@code null} = sem restrição (ADMIN). */
    public Page<InsightProcessingItem> findProcessing(
            InsightsProcessingFilter filter, Pageable pageable, String source, Set<Integer> businessUnitIds) {
        Specification<CallAudioFile> spec = withProcessingFilters(filter, source);
        if (businessUnitIds != null) {
            spec = spec.and(InsightsSpecifications.restrictedToBusinessUnits(businessUnitIds));
        }
        Page<CallAudioFile> page = audioFileRepository.findAll(spec, pageable);
        return page.map(a -> InsightProcessingItem.from(a,
                "pending".equals(a.getStatus()) && a.getIngestedAt() != null
                        ? (int) audioFileRepository.countPendingBefore(a.getIngestedAt()) + 1
                        : null));
    }

    private Specification<CallAudioFile> withProcessingFilters(InsightsProcessingFilter filter, String source) {
        return (root, query, cb) -> {
            var predicates = cb.equal(root.get("source"), source);
            if (filter.status() != null && !filter.status().isBlank()) {
                predicates = cb.and(predicates, cb.equal(root.get("status"), filter.status()));
            }
            if (filter.dateFrom() != null) {
                predicates = cb.and(predicates, cb.greaterThanOrEqualTo(root.get("ingestedAt"), filter.dateFrom()));
            }
            if (filter.dateTo() != null) {
                predicates = cb.and(predicates, cb.lessThanOrEqualTo(root.get("ingestedAt"), filter.dateTo()));
            }
            if (filter.fileName() != null && !filter.fileName().isBlank()) {
                predicates = cb.and(predicates,
                        cb.like(cb.lower(root.get("wavPath")), "%" + filter.fileName().toLowerCase() + "%"));
            }
            return predicates;
        };
    }

    private List<Long> resolveRestrictedIds(InsightsFilter filter, boolean isAdmin) {
        Set<Long> restricted = null;

        if (hasText(filter.text())) {
            restricted = intersect(restricted, segmentRepository.findAudioFileIdsByTextSearch(filter.text()));
        }
        if (hasText(filter.phrase())) {
            restricted = intersect(restricted, segmentRepository.findAudioFileIdsByPhraseSearch(filter.phrase()));
        }
        if (hasText(filter.toneCliente())) {
            restricted = intersect(restricted,
                    segmentRepository.findAudioFileIdsBySpeakerAndTone("cliente", filter.toneCliente()));
        }
        if (hasText(filter.toneAtendente())) {
            restricted = intersect(restricted,
                    segmentRepository.findAudioFileIdsBySpeakerAndTone("agente", filter.toneAtendente()));
        }
        if (hasText(filter.categoria())) {
            restricted = intersect(restricted, insightRepository.findAudioFileIdsByCategoria(filter.categoria()));
        }
        if (hasText(filter.criticidade())) {
            restricted = intersect(restricted, insightRepository.findAudioFileIdsByCriticidade(filter.criticidade()));
        }
        if (hasText(filter.findingType())) {
            restricted = intersect(restricted, findingRepository.findAudioFileIdsByTipo(filter.findingType()));
        }
        if (filter.notaMin() != null) {
            restricted = intersect(restricted, evaluationRepository.findAudioFileIdsByNotaMin(filter.notaMin()));
        }
        if (filter.notaMax() != null) {
            restricted = intersect(restricted, evaluationRepository.findAudioFileIdsByNotaMax(filter.notaMax()));
        }
        if (filter.isFailed() != null) {
            restricted = intersect(restricted, evaluationRepository.findAudioFileIdsByIsFailed(filter.isFailed()));
        }
        if (hasText(filter.transferTargetExtension())) {
            restricted = intersect(restricted,
                    transferEventRepository.findAudioFileIdsByTargetExtension(filter.transferTargetExtension()));
        }
        if (hasText(filter.transferTargetAgentName())) {
            restricted = intersect(restricted,
                    transferEventRepository.findAudioFileIdsByTargetAgentName(filter.transferTargetAgentName()));
        }
        // ADMIN-only (decisão 8) — mesmo que o parâmetro chegue preenchido de um usuário
        // comum (contornando o frontend), nunca é aplicado sem isAdmin=true.
        if (isAdmin && hasText(filter.targetSwitchCallId())) {
            restricted = intersect(restricted,
                    transferEventRepository.findAudioFileIdsByTargetSwitchCallId(filter.targetSwitchCallId()));
        }

        return restricted != null ? List.copyOf(restricted) : null;
    }

    private static Set<Long> intersect(Set<Long> current, List<Long> next) {
        if (current == null) {
            return new HashSet<>(next);
        }
        current.retainAll(next);
        return current;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
