package com.asteriskia.config;

public record LoginResponse(
        String token,
        String type,
        int expiresInHours,
        Integer extension,
        String displayName,
        boolean firstLoginCompleted) {}
