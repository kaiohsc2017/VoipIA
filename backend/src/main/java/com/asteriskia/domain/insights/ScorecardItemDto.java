package com.asteriskia.domain.insights;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** ScorecardItemDto — item de ficha exposto/recebido pela API (CRUD de fichas, Fase 1 QM). */
public record ScorecardItemDto(
        Long id,
        @NotNull Integer ordem,
        @NotBlank String pergunta,
        @NotNull BigDecimal peso,
        @NotNull Integer notaMaxima,
        @NotNull Boolean isCritical
) {
    public static ScorecardItemDto from(ScorecardItem item) {
        return new ScorecardItemDto(item.getId(), item.getOrdem(), item.getPergunta(),
                item.getPeso(), item.getNotaMaxima(), item.getIsCritical());
    }
}
