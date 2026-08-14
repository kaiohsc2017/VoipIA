package com.asteriskia.domain.callcenter.interaction;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/** CcInteractionSpecifications — filtros do relatório analítico de chamada (Fase 9c), mesmo
 * padrão de {@code CcRecordingSpecifications} — pública porque é consumida pelo pacote
 * {@code reports} (relatório), diferente daquela, que só é usada dentro do próprio pacote. */
public final class CcInteractionSpecifications {

    private CcInteractionSpecifications() {}

    public static Specification<CcInteraction> queuedAtFrom(LocalDateTime from) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("queuedAt"), from);
    }

    public static Specification<CcInteraction> queuedAtTo(LocalDateTime to) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("queuedAt"), to);
    }

    public static Specification<CcInteraction> queueIdEquals(Long queueId) {
        return (root, query, cb) -> cb.equal(root.get("queue").get("id"), queueId);
    }

    public static Specification<CcInteraction> agentIdEquals(Long agentId) {
        return (root, query, cb) -> cb.equal(root.get("agent").get("id"), agentId);
    }

    public static Specification<CcInteraction> directionEquals(Direction direction) {
        return (root, query, cb) -> cb.equal(root.get("direction"), direction);
    }

    public static Specification<CcInteraction> npsScoreFrom(BigDecimal min) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("npsScore"), min);
    }

    public static Specification<CcInteraction> npsScoreTo(BigDecimal max) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("npsScore"), max);
    }

    public static Specification<CcInteraction> idIn(List<Long> ids) {
        return (root, query, cb) -> root.get("id").in(ids);
    }
}
