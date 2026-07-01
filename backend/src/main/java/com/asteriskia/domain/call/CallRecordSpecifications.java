package com.asteriskia.domain.call;

import org.springframework.data.jpa.domain.Specification;

/**
 * CallRecordSpecifications — monta a query dinâmica combinando os filtros
 * opcionais de CallRecordFilter (JPA Criteria API via Spring Data Specification).
 */
public final class CallRecordSpecifications {

    private CallRecordSpecifications() {}

    public static Specification<CallRecord> withFilters(CallRecordFilter filter) {
        return (root, query, cb) -> {
            var predicates = cb.conjunction();

            if (filter.uraId() != null) {
                predicates = cb.and(predicates, cb.equal(root.get("uraId"), filter.uraId()));
            }
            if (hasText(filter.callerNumber())) {
                predicates = cb.and(predicates, cb.like(root.get("callerNumber"), "%" + filter.callerNumber() + "%"));
            }
            if (hasText(filter.clientName())) {
                predicates = cb.and(predicates, cb.like(cb.lower(root.get("clientName")), "%" + filter.clientName().toLowerCase() + "%"));
            }
            if (hasText(filter.ramal())) {
                predicates = cb.and(predicates, cb.like(root.get("reportedRamal"), "%" + filter.ramal() + "%"));
            }
            if (hasText(filter.callType())) {
                predicates = cb.and(predicates, cb.like(cb.lower(root.get("callType")), "%" + filter.callType().toLowerCase() + "%"));
            }
            if (hasText(filter.jiraIssueKey())) {
                predicates = cb.and(predicates, cb.like(cb.lower(root.get("jiraIssueKey")), "%" + filter.jiraIssueKey().toLowerCase() + "%"));
            }
            if (hasText(filter.transcriptionText())) {
                predicates = cb.and(predicates, cb.like(cb.lower(root.get("transcription")), "%" + filter.transcriptionText().toLowerCase() + "%"));
            }
            if (hasText(filter.priority())) {
                predicates = cb.and(predicates, cb.equal(cb.lower(root.get("priority")), filter.priority().toLowerCase()));
            }
            if (filter.dateFrom() != null) {
                predicates = cb.and(predicates, cb.greaterThanOrEqualTo(root.get("callDate"), filter.dateFrom()));
            }
            if (filter.dateTo() != null) {
                predicates = cb.and(predicates, cb.lessThanOrEqualTo(root.get("callDate"), filter.dateTo()));
            }

            return predicates;
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
