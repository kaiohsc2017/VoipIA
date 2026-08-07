package com.asteriskia.domain.callcenter.flow;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** FlowRequest — entrada de criação/atualização do metadado de um fluxo (Fase 5a). */
public record FlowRequest(
        @NotBlank String name,
        String description,
        @NotNull @Pattern(regexp = "^(voice|chat|both)$", message = "Canal deve ser voice, chat ou both")
                String channel,
        @Pattern(regexp = "^6\\d{3}$", message = "Ramal deve estar na faixa 6000-6999")
                String entryExtension,
        Integer businessUnitId) {}
