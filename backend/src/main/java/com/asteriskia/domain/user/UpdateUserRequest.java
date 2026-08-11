package com.asteriskia.domain.user;

import java.time.LocalDate;
import java.util.List;

public record UpdateUserRequest(
        String displayName,
        String password,
        Boolean isActive,
        String role,
        List<Integer> businessUnitIds,
        LocalDate accessExpiresAt,
        Boolean accessIndeterminate) {}
