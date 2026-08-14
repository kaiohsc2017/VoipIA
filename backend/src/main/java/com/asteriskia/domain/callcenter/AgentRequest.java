package com.asteriskia.domain.callcenter;

import jakarta.validation.constraints.NotBlank;

/** AgentRequest — payload de criação/atualização de agente (POST/PUT /callcenter/agentes). */
public record AgentRequest(
        @NotBlank String name,
        Integer userId,
        Integer businessUnitId,
        @NotBlank String extension,
        /** Fase 7c — limite de chats simultâneos deste agente; nulo ou zero = "sem valor próprio",
         * vale o limite da fila (ver {@code ChatBlendingService.resolveLimit}). Qualquer valor
         * {@code > 0} sempre prevalece sobre o da fila. */
        Integer maxConcurrentChats) {}
