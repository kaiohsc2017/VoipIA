package com.asteriskia.domain.accessgroup;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record GroupRequest(
        @NotBlank(message = "Nome obrigatório") String name,
        String description,
        List<PermissionEntry> permissions) {}
