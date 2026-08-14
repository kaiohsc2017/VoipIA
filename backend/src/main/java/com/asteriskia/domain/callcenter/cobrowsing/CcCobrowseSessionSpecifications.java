package com.asteriskia.domain.callcenter.cobrowsing;

import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.jpa.domain.Specification;

/**
 * CcCobrowseSessionSpecifications — escopo de BU da listagem de co-browsing (Fase 17c), mesma
 * política de {@code CcRecordingSpecifications}. {@code business_unit_id} aqui é uma coluna
 * desnormalizada simples (não uma relação JPA — ver {@link CcCobrowseSession}), por isso o filtro
 * compara direto contra {@code Long}, convertendo o {@code Set<Integer>} de
 * {@code BusinessUnitContext} uma única vez.
 */
final class CcCobrowseSessionSpecifications {

    private CcCobrowseSessionSpecifications() {}

    static Specification<CcCobrowseSession> restrictedToBusinessUnits(Set<Integer> allowedIds) {
        Set<Long> allowed = allowedIds.stream().map(Integer::longValue).collect(Collectors.toSet());
        return (root, query, cb) ->
                cb.or(cb.isNull(root.get("businessUnitId")), root.get("businessUnitId").in(allowed));
    }
}
