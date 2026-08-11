package com.asteriskia.domain.config;

public record ConfigDTO(
        String key, String value, boolean isSecret, String description, String updatedAt) {}
