package com.asteriskia.domain.user;

import java.time.LocalDate;
import java.util.List;

public record UpdateUserRequest(
        String displayName,
        String password,
        Boolean isActive,
        String role,
        /** Grupo de acesso customizado (RBAC granular) — se informado, tem precedência sobre
         * {@code role} na resolução do grupo do usuário. */
        Integer accessGroupId,
        List<Integer> businessUnitIds,
        LocalDate accessExpiresAt,
        Boolean accessIndeterminate) {}
