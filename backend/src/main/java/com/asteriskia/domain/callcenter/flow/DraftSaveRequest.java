package com.asteriskia.domain.callcenter.flow;

import jakarta.validation.constraints.NotBlank;

/** DraftSaveRequest — grafo (JSON nativo do React Flow) a persistir na versão DRAFT atual. */
public record DraftSaveRequest(@NotBlank String graph) {}
