package com.asteriskia.domain.accessgroup;

public record PermissionEntry(String resourceKey, Boolean canRead, Boolean canWrite) {}
