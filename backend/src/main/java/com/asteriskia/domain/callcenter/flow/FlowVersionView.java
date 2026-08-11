package com.asteriskia.domain.callcenter.flow;

import java.time.LocalDateTime;

/**
 * FlowVersionView — projeção de uma versão do fluxo. {@code graph} vem preenchido só no detalhe
 * (GET de uma versão específica) — na listagem de histórico ({@code includeGraph=false}) fica
 * {@code null} para não pesar o payload.
 */
public record FlowVersionView(
        Long id,
        Long flowId,
        Integer versionNumber,
        FlowStatus status,
        String graph,
        String notes,
        LocalDateTime publishedAt,
        String publishedBy,
        LocalDateTime createdAt) {

    public static FlowVersionView from(CcFlowVersion version, boolean includeGraph) {
        return new FlowVersionView(
                version.getId(),
                version.getFlow().getId(),
                version.getVersionNumber(),
                version.getStatus(),
                includeGraph ? version.getGraph() : null,
                version.getNotes(),
                version.getPublishedAt(),
                version.getPublishedBy(),
                version.getCreatedAt());
    }
}
