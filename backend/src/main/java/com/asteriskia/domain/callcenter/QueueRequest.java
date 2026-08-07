package com.asteriskia.domain.callcenter;

import jakarta.validation.constraints.NotBlank;

/**
 * QueueRequest — payload de criação/atualização de fila (POST/PUT /callcenter/filas).
 *
 * <p>{@code recordingEnabled}/{@code consentMessagePath} (Fase 3): quando {@code recordingEnabled}
 * vem {@code null} na criação, o service assume {@code true} (grava por padrão, mesma política de
 * fail-open aplicada pelo dialplan quando o backend não responde).
 */
public record QueueRequest(
        @NotBlank String name,
        @NotBlank String displayName,
        Integer businessUnitId,
        String strategy,
        Integer timeoutSeconds,
        Boolean recordingEnabled,
        String consentMessagePath) {}
