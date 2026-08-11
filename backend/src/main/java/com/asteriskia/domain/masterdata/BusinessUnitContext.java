package com.asteriskia.domain.masterdata;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * BusinessUnitContext — lê as BUs (Unidades de Negócio) do usuário autenticado
 * a partir das authorities "BU_&lt;id&gt;" (populadas pelo JwtAuthFilter a partir
 * da claim "bu" do JWT — ver JwtService).
 *
 * ADMIN (ROLE_ADMIN) sempre enxerga todos os dados — mesmo padrão já usado
 * pelo RBAC granular (perm) — então {@link #isRestricted()} retorna false
 * para ADMIN e repositórios/serviços não devem aplicar filtro de BU nesse caso.
 */
public final class BusinessUnitContext {

    private static final String BU_AUTHORITY_PREFIX = "BU_";

    private BusinessUnitContext() {}

    /** true se o usuário atual deve ter os dados restritos às suas BUs (não-ADMIN). */
    public static boolean isRestricted() {
        return !hasAuthority("ROLE_ADMIN");
    }

    /** IDs das BUs do usuário atual — vazio se ADMIN (sem restrição) ou sem BU atribuída. */
    public static Set<Integer> currentBusinessUnitIds() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return Set.of();
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith(BU_AUTHORITY_PREFIX))
                .map(a -> a.substring(BU_AUTHORITY_PREFIX.length()))
                .map(Integer::parseInt)
                .collect(Collectors.toSet());
    }

    private static boolean hasAuthority(String authority) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(a -> authority.equals(a.getAuthority()));
    }
}
