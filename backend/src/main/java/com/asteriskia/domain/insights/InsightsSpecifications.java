package com.asteriskia.domain.insights;

import com.asteriskia.domain.callcenter.recording.CcRecording;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Set;

/**
 * InsightsSpecifications — monta a query dinâmica de CallAudioFile a partir de
 * InsightsFilter. Os critérios de texto/frase/tom/categoria são resolvidos
 * ANTES (fora desta classe, em InsightsQueryService) para um conjunto de IDs
 * — vivem em call_transcript_segments/call_insights, não em call_audio_files
 * — e chegam aqui já como uma lista de IDs permitidos, mantendo esta classe
 * simples (só Criteria API sobre colunas da própria CallAudioFile).
 */
public final class InsightsSpecifications {

    private InsightsSpecifications() {}

    /**
     * Escopo por BU (fecha parte do gap documentado em CLAUDE.md — Insights do Call Center não
     * filtrava por BU). Só usada por {@code source="callcenter"} — Insights (Verint) nunca teve
     * conceito de BU e continua deliberadamente de fora. Fail-open (mesmo padrão de
     * {@code CallRecordSpecifications.restrictedToBusinessUnits}): gravação sem
     * {@code ccRecordingId} ou cuja {@code CcRecording} não tem BU atribuída fica visível a
     * todos — a BU é opcional no cadastro de fila, não obrigatória.
     */
    public static Specification<CallAudioFile> restrictedToBusinessUnits(Set<Integer> allowedBusinessUnitIds) {
        return (root, query, cb) -> {
            // Subquery deliberadamente NÃO correlacionada ao root — seleciona todo id de
            // CcRecording cuja BU já é permitida/nula, e o filtro real acontece no IN externo
            // (linha de baixo). Mesmo formato de CallRecordSpecifications.restrictedToBusinessUnits.
            var subquery = query.subquery(Long.class);
            var ccRecordingRoot = subquery.from(CcRecording.class);
            subquery.select(ccRecordingRoot.get("id"));
            subquery.where(
                    cb.or(
                            cb.isNull(ccRecordingRoot.get("businessUnit")),
                            ccRecordingRoot.get("businessUnit").get("id").in(allowedBusinessUnitIds)));
            return cb.or(cb.isNull(root.get("ccRecordingId")), root.get("ccRecordingId").in(subquery));
        };
    }

    /** Sobrecarga original — mantém o comportamento de sempre (source='verint') para os
     * chamadores existentes (tela Insights). */
    public static Specification<CallAudioFile> withFilters(InsightsFilter filter, List<Long> restrictedToIds) {
        return withFilters(filter, restrictedToIds, "verint");
    }

    /** source parametrizado (Fase 8 do Call Center) — mesma lógica de filtro, aplicada a
     * source='callcenter' pela tela de Insights do Call Center em vez de source='verint'. */
    public static Specification<CallAudioFile> withFilters(
            InsightsFilter filter, List<Long> restrictedToIds, String source) {
        return (root, query, cb) -> {
            var predicates = cb.equal(root.get("source"), source);

            if (filter.id() != null) {
                predicates = cb.and(predicates, cb.equal(root.get("id"), filter.id()));
            }
            if (filter.dateFrom() != null) {
                predicates = cb.and(predicates, cb.greaterThanOrEqualTo(root.get("callStarttime"), filter.dateFrom()));
            }
            if (filter.dateTo() != null) {
                predicates = cb.and(predicates, cb.lessThanOrEqualTo(root.get("callStarttime"), filter.dateTo()));
            }
            if (filter.agentName() != null && !filter.agentName().isBlank()) {
                predicates = cb.and(predicates,
                        cb.like(cb.lower(root.get("agentName")), "%" + filter.agentName().toLowerCase() + "%"));
            }
            if (filter.direction() != null && !filter.direction().isBlank()) {
                predicates = cb.and(predicates, cb.equal(root.get("direction"), filter.direction()));
            }
            if (filter.skill() != null && !filter.skill().isBlank()) {
                predicates = cb.and(predicates,
                        cb.like(cb.lower(root.get("skill")), "%" + filter.skill().toLowerCase() + "%"));
            }
            if (filter.durationMin() != null) {
                predicates = cb.and(predicates, cb.greaterThanOrEqualTo(root.get("durationSeconds"), filter.durationMin()));
            }
            if (filter.durationMax() != null) {
                predicates = cb.and(predicates, cb.lessThanOrEqualTo(root.get("durationSeconds"), filter.durationMax()));
            }
            if (filter.extension() != null && !filter.extension().isBlank()) {
                predicates = cb.and(predicates, cb.like(root.get("extension"), "%" + filter.extension() + "%"));
            }
            if (filter.disconnectedBy() != null && !filter.disconnectedBy().isBlank()) {
                predicates = cb.and(predicates, cb.equal(root.get("disconnectedBy"), filter.disconnectedBy()));
            }
            if (Boolean.TRUE.equals(filter.hasHold())) {
                predicates = cb.and(predicates, cb.greaterThan(root.get("numberOfHolds"), 0));
            } else if (Boolean.FALSE.equals(filter.hasHold())) {
                predicates = cb.and(predicates, cb.or(
                        cb.isNull(root.get("numberOfHolds")),
                        cb.lessThanOrEqualTo(root.get("numberOfHolds"), 0)));
            }
            if (filter.wrapupTimeMin() != null) {
                predicates = cb.and(predicates, cb.greaterThanOrEqualTo(root.get("wrapupTime"), filter.wrapupTimeMin()));
            }
            if (filter.wrapupTimeMax() != null) {
                predicates = cb.and(predicates, cb.lessThanOrEqualTo(root.get("wrapupTime"), filter.wrapupTimeMax()));
            }
            if (filter.agentLoginId() != null && !filter.agentLoginId().isBlank()) {
                predicates = cb.and(predicates,
                        cb.like(root.get("agentLoginId"), "%" + filter.agentLoginId() + "%"));
            }
            if (filter.telCliente() != null && !filter.telCliente().isBlank()) {
                // Direction-aware, mesmo critério de InsightsAudioFileDto.resolveDisplayAni:
                // outbound busca em dnis (o que é exibido como "Tel. Cliente" nesse caso);
                // qualquer outra direção (inclusive null) busca em ani.
                String needle = "%" + filter.telCliente() + "%";
                var isOutbound = cb.equal(root.get("direction"), "outbound");
                var outboundMatch = cb.and(isOutbound, cb.like(root.get("dnis"), needle));
                var notOutboundMatch = cb.and(
                        cb.or(cb.isNull(root.get("direction")), cb.notEqual(root.get("direction"), "outbound")),
                        cb.like(root.get("ani"), needle));
                predicates = cb.and(predicates, cb.or(outboundMatch, notOutboundMatch));
            }
            if (restrictedToIds != null) {
                predicates = cb.and(predicates, restrictedToIds.isEmpty()
                        ? cb.disjunction()
                        : root.get("id").in(restrictedToIds));
            }

            return predicates;
        };
    }
}
