package com.asteriskia.domain.callcenter.flow;

import java.time.LocalDateTime;

/** FlowView — projeção de listagem/detalhe de um fluxo, sem o grafo (ver {@link FlowVersionView}). */
public record FlowView(
        Long id,
        String name,
        String description,
        String channel,
        String entryExtension,
        Integer businessUnitId,
        Boolean active,
        Long publishedVersionId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static FlowView from(CcFlow flow) {
        return new FlowView(
                flow.getId(),
                flow.getName(),
                flow.getDescription(),
                flow.getChannel(),
                flow.getEntryExtension(),
                flow.getBusinessUnit() == null ? null : flow.getBusinessUnit().getId(),
                flow.getActive(),
                flow.getPublishedVersionId(),
                flow.getCreatedAt(),
                flow.getUpdatedAt());
    }
}
