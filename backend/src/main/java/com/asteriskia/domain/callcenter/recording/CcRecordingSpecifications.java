package com.asteriskia.domain.callcenter.recording;

import java.time.LocalDateTime;
import java.util.Set;
import org.springframework.data.jpa.domain.Specification;

/** CcRecordingSpecifications — filtros de listagem + escopo por BU (mesma política de CallCenterSpecifications). */
final class CcRecordingSpecifications {

    private CcRecordingSpecifications() {}

    static Specification<CcRecording> restrictedToBusinessUnits(Set<Integer> allowedIds) {
        return (root, query, cb) ->
                cb.or(
                        cb.isNull(root.get("businessUnit")),
                        root.get("businessUnit").get("id").in(allowedIds));
    }

    static Specification<CcRecording> queueIdEquals(Long queueId) {
        return (root, query, cb) -> cb.equal(root.get("queue").get("id"), queueId);
    }

    static Specification<CcRecording> startedAtFrom(LocalDateTime from) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("startedAt"), from);
    }

    static Specification<CcRecording> startedAtTo(LocalDateTime to) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("startedAt"), to);
    }
}
