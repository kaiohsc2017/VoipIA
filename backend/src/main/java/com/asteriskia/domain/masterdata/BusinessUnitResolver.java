package com.asteriskia.domain.masterdata;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * BusinessUnitResolver — resolução de IDs de BusinessUnit para as entidades correspondentes,
 * compartilhado entre ClientController e OperationController (sync de BUs), extraído de
 * MasterDataController (fase 10 da refatoração).
 */
public final class BusinessUnitResolver {

    private BusinessUnitResolver() {}

    /** Resolve os IDs de BusinessUnit informados; vazio se algum ID não existir. */
    public static Optional<Set<BusinessUnit>> resolve(
            BusinessUnitRepository buRepo, List<Integer> businessUnitIds) {
        List<Integer> ids = businessUnitIds == null ? List.of() : businessUnitIds;
        List<BusinessUnit> found = buRepo.findAllById(ids);
        if (found.size() != Set.copyOf(ids).size()) {
            return Optional.empty();
        }
        return Optional.of(new HashSet<>(found));
    }
}
