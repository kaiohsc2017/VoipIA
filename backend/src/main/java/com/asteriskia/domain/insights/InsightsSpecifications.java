package com.asteriskia.domain.insights;

import org.springframework.data.jpa.domain.Specification;

import java.util.List;

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

    public static Specification<CallAudioFile> withFilters(InsightsFilter filter, List<Long> restrictedToIds) {
        return (root, query, cb) -> {
            var predicates = cb.conjunction();

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
            if (restrictedToIds != null) {
                predicates = cb.and(predicates, restrictedToIds.isEmpty()
                        ? cb.disjunction()
                        : root.get("id").in(restrictedToIds));
            }

            return predicates;
        };
    }
}
