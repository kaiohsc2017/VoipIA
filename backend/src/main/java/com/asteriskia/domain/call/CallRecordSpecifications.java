package com.asteriskia.domain.call;

import com.asteriskia.domain.ura.Ura;
import java.util.Set;
import org.springframework.data.jpa.domain.Specification;

/**
 * CallRecordSpecifications — monta a query dinâmica combinando os filtros opcionais de
 * CallRecordFilter (JPA Criteria API via Spring Data Specification).
 */
public final class CallRecordSpecifications {

    private CallRecordSpecifications() {}

    /**
     * Controle de acesso por BU: restringe a chamadas cuja URA pertence a uma das BUs do usuário —
     * URAs sem BU definida (ex.: a legada id=1) ficam visíveis a todos, tratadas como "sem
     * restrição" (BU é opcional na URA).
     */
    public static Specification<CallRecord> restrictedToBusinessUnits(
            Set<Integer> allowedBusinessUnitIds) {
        return (root, query, cb) -> {
            var subquery = query.subquery(Integer.class);
            var uraRoot = subquery.from(Ura.class);
            subquery.select(uraRoot.get("id"));
            subquery.where(
                    cb.or(
                            cb.isNull(uraRoot.get("businessUnit")),
                            uraRoot.get("businessUnit").get("id").in(allowedBusinessUnitIds)));
            return root.get("uraId").in(subquery);
        };
    }

    public static Specification<CallRecord> withFilters(CallRecordFilter filter) {
        return (root, query, cb) -> {
            var predicates = cb.conjunction();

            if (filter.uraId() != null) {
                predicates = cb.and(predicates, cb.equal(root.get("uraId"), filter.uraId()));
            }
            if (hasText(filter.callerNumber())) {
                predicates =
                        cb.and(
                                predicates,
                                cb.like(
                                        root.get("callerNumber"),
                                        "%" + filter.callerNumber() + "%"));
            }
            if (hasText(filter.clientName())) {
                predicates =
                        cb.and(
                                predicates,
                                cb.like(
                                        cb.lower(root.get("clientName")),
                                        "%" + filter.clientName().toLowerCase() + "%"));
            }
            if (hasText(filter.ramal())) {
                predicates =
                        cb.and(
                                predicates,
                                cb.like(root.get("reportedRamal"), "%" + filter.ramal() + "%"));
            }
            if (hasText(filter.callType())) {
                predicates =
                        cb.and(
                                predicates,
                                cb.like(
                                        cb.lower(root.get("callType")),
                                        "%" + filter.callType().toLowerCase() + "%"));
            }
            if (hasText(filter.jiraIssueKey())) {
                predicates =
                        cb.and(
                                predicates,
                                cb.like(
                                        cb.lower(root.get("jiraIssueKey")),
                                        "%" + filter.jiraIssueKey().toLowerCase() + "%"));
            }
            if (hasText(filter.transcriptionText())) {
                predicates =
                        cb.and(
                                predicates,
                                cb.like(
                                        cb.lower(root.get("transcription")),
                                        "%" + filter.transcriptionText().toLowerCase() + "%"));
            }
            if (hasText(filter.priority())) {
                predicates =
                        cb.and(
                                predicates,
                                cb.equal(
                                        cb.lower(root.get("priority")),
                                        filter.priority().toLowerCase()));
            }
            if (filter.dateFrom() != null) {
                predicates =
                        cb.and(
                                predicates,
                                cb.greaterThanOrEqualTo(root.get("callDate"), filter.dateFrom()));
            }
            if (filter.dateTo() != null) {
                predicates =
                        cb.and(
                                predicates,
                                cb.lessThanOrEqualTo(root.get("callDate"), filter.dateTo()));
            }
            if (hasText(filter.subjectTag())) {
                predicates =
                        cb.and(
                                predicates,
                                cb.equal(
                                        cb.lower(root.get("subjectTag")),
                                        filter.subjectTag().toLowerCase()));
            }
            if (hasText(filter.jiraResolution())) {
                predicates =
                        cb.and(
                                predicates,
                                cb.equal(
                                        cb.lower(root.get("jiraResolution")),
                                        filter.jiraResolution().toLowerCase()));
            }

            return predicates;
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
