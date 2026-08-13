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
        // Fase 19 (Parte III): a faixa exata (antes fixa em 6000-6999) passou a ser configurável
        // via CcSettingsService — a anotação só garante o formato numérico de 4 dígitos; o range
        // vigente é validado em CallCenterFlowService.validateExtensionRange.
        @Pattern(regexp = "^\\d{4}$", message = "Ramal deve ter 4 dígitos")
                String entryExtension,
        Integer businessUnitId) {}
