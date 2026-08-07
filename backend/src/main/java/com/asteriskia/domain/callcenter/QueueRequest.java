package com.asteriskia.domain.callcenter;

import jakarta.validation.constraints.NotBlank;

/** QueueRequest — payload de criação/atualização de fila (POST/PUT /callcenter/filas). */
public record QueueRequest(
        @NotBlank String name,
        @NotBlank String displayName,
        Integer businessUnitId,
        String strategy,
        Integer timeoutSeconds) {}
