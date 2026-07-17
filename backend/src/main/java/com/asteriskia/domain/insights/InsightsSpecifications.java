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

            if (filter.dateFrom() != null) {
                predicates = cb.and(predicates, cb.greaterThanOrEqualTo(root.get("callStarttime"), filter.dateFrom()));
            }
            if (filter.dateTo() != null) {
                predicates = cb.and(predicates, cb.lessThanOrEqualTo(root.get("callStarttime"), filter.dateTo()));
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
