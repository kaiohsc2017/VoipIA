package com.asteriskia.domain.masterdata;

import java.util.List;
import java.util.function.Function;

/**
 * MasterDataScopeFilter — filtro de escopo por BU compartilhado entre ClientController e
 * OperationController, extraído de MasterDataController (fase 10 da refatoração).
 */
public final class MasterDataScopeFilter {

    private MasterDataScopeFilter() {}

    /**
     * Controle de acesso por BU: mantém apenas os itens sem BU (visíveis a todos — BU é opcional no
     * cadastro) ou com ao menos uma BU em comum com o usuário logado. ADMIN não é filtrado.
     */
    public static <T> List<T> filterByBusinessUnitScope(
            List<T> items, Function<T, List<Integer>> businessUnitIdsOf) {
        if (!BusinessUnitContext.isRestricted()) {
            return items;
        }
        var allowed = BusinessUnitContext.currentBusinessUnitIds();
        return items.stream()
                .filter(
                        item -> {
                            List<Integer> ids = businessUnitIdsOf.apply(item);
                            return ids.isEmpty() || ids.stream().anyMatch(allowed::contains);
                        })
                .toList();
    }
}
