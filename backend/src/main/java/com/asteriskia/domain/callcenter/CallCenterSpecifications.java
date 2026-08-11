package com.asteriskia.domain.callcenter;

import java.util.Set;
import org.springframework.data.jpa.domain.Specification;

/** CallCenterSpecifications — escopo por BU (mesma política de Ura/CallRecord: sem BU = visível a todos). */
final class CallCenterSpecifications {

    private CallCenterSpecifications() {}

    static Specification<CcAgent> agentRestrictedToBusinessUnits(Set<Integer> allowedIds) {
        return (root, query, cb) ->
                cb.or(
                        cb.isNull(root.get("businessUnit")),
                        root.get("businessUnit").get("id").in(allowedIds));
    }

    static Specification<CcQueue> queueRestrictedToBusinessUnits(Set<Integer> allowedIds) {
        return (root, query, cb) ->
                cb.or(
                        cb.isNull(root.get("businessUnit")),
                        root.get("businessUnit").get("id").in(allowedIds));
    }
}
