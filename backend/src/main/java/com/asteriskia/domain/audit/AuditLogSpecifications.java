package com.asteriskia.domain.audit;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * AuditLogSpecifications — filtros dinâmicos para {@link AuditLogRepository}.
 *
 * Usa Specification em vez de JPQL com "(:param IS NULL OR campo = :param)"
 * de propósito: esse padrão faz o driver JDBC do Postgres falhar com
 * "could not determine data type of parameter" (ou, com CAST forçado,
 * "cannot cast type bytea to timestamp") sempre que o parâmetro vem null —
 * o caso mais comum (listar sem filtro). Com Specification, um filtro null
 * simplesmente não vira predicado nenhum — nenhum parâmetro é enviado ao
 * banco pra ele, então não há ambiguidade de tipo a resolver.
 */
final class AuditLogSpecifications {

    private AuditLogSpecifications() {}

    static Specification<AuditLog> withFilters(String username, String action, LocalDateTime from, LocalDateTime to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (username != null) predicates.add(cb.equal(root.get("username"), username));
            if (action != null) predicates.add(cb.equal(root.get("action"), action));
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null) predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            query.orderBy(cb.desc(root.get("createdAt")));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
