package com.asteriskia.domain.callcenter;

import jakarta.validation.constraints.NotBlank;

/** AgentRequest — payload de criação/atualização de agente (POST/PUT /callcenter/agentes). */
public record AgentRequest(
        @NotBlank String name, Integer userId, Integer businessUnitId, @NotBlank String extension) {}
