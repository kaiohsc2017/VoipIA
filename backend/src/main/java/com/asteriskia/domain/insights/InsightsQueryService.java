package com.asteriskia.domain.insights;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

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

    public Page<InsightsListItem> search(InsightsFilter filter, Pageable pageable) {
        List<Long> restrictedToIds = resolveRestrictedIds(filter);

        Page<CallAudioFile> page = audioFileRepository.findAll(
                InsightsSpecifications.withFilters(filter, restrictedToIds), pageable);

        List<Long> pageIds = page.getContent().stream().map(CallAudioFile::getId).toList();
        Map<Long, CallInsight> insightsByAudioFileId = insightRepository.findByAudioFileIdIn(pageIds).stream()
                .collect(Collectors.toMap(CallInsight::getAudioFileId, i -> i));

        return page.map(audioFile ->
                InsightsListItem.from(audioFile, insightsByAudioFileId.get(audioFile.getId())));
    }

    public InsightsDetailResponse detail(Long id) {
        CallAudioFile audioFile = audioFileRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Chamada não encontrada: id=" + id));
        List<CallTranscriptSegment> segments = segmentRepository.findByAudioFileIdOrderByStartMsAsc(id);
        CallInsight insight = insightRepository.findByAudioFileId(id).orElse(null);
        List<CallInsightFinding> findings = findingRepository.findByAudioFileIdOrderByIdAsc(id);
        return new InsightsDetailResponse(audioFile, segments, insight, findings);
    }

    public CallAudioFile findAudioFileById(Long id) {
        return audioFileRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Chamada não encontrada: id=" + id));
    }

    public InsightsDashboardSummary dashboard() {
        long total = audioFileRepository.count();

        Map<String, Long> porCriticidade = new HashMap<>();
        for (Object[] row : insightRepository.countByCriticidade()) {
            porCriticidade.put((String) row[0], (Long) row[1]);
        }

        Map<String, Long> porCategoria = new HashMap<>();
        for (Object[] row : insightRepository.countByCategoria()) {
            porCategoria.put((String) row[0], (Long) row[1]);
        }

        Map<String, Long> achadosPorTipo = new HashMap<>();
        for (Object[] row : findingRepository.countByTipo()) {
            achadosPorTipo.put((String) row[0], (Long) row[1]);
        }

        return new InsightsDashboardSummary(total, porCriticidade, porCategoria, achadosPorTipo);
    }

    /** Aba "Processamento" — status/fila de cada arquivo descoberto em /opt/audio. Posição na
     * fila calculada só para status='pending' (FIFO por ordem de descoberta); demais status
     * ficam com queuePosition=null. */
    public Page<InsightProcessingItem> findProcessing(InsightsProcessingFilter filter, Pageable pageable) {
        Page<CallAudioFile> page = audioFileRepository.findAll(withProcessingFilters(filter), pageable);
        return page.map(a -> InsightProcessingItem.from(a,
                "pending".equals(a.getStatus()) && a.getIngestedAt() != null
                        ? (int) audioFileRepository.countPendingBefore(a.getIngestedAt()) + 1
                        : null));
    }

    private Specification<CallAudioFile> withProcessingFilters(InsightsProcessingFilter filter) {
        return (root, query, cb) -> {
            var predicates = cb.conjunction();
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

    private List<Long> resolveRestrictedIds(InsightsFilter filter) {
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
