package com.asteriskia.domain.insights;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.time.LocalDateTime;
import java.util.List;

/** ScorecardDto — ficha de avaliação completa (cabeçalho + itens) exposta pela API. */
public record ScorecardDto(
        Long id,
        @NotBlank String name,
        String description,
        Boolean isActive,
        Integer version,
        @NotEmpty @Valid List<ScorecardItemDto> items,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ScorecardDto from(QualityScorecard scorecard, List<ScorecardItem> items) {
        return new ScorecardDto(scorecard.getId(), scorecard.getName(), scorecard.getDescription(),
                scorecard.getIsActive(), scorecard.getVersion(),
                items.stream().map(ScorecardItemDto::from).toList(),
                scorecard.getCreatedAt(), scorecard.getUpdatedAt());
    }
}
