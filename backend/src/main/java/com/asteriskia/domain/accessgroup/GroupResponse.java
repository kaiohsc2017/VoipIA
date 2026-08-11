package com.asteriskia.domain.accessgroup;

import java.util.List;

public record GroupResponse(
        Integer id,
        String name,
        String description,
        Boolean isSystem,
        List<PermissionEntry> permissions) {}
