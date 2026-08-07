package com.asteriskia.domain.callcenter.interaction;

import jakarta.validation.constraints.NotNull;

/** DispositionRequest — tabulação aplicada por um agente ao encerrar uma interação. */
public record DispositionRequest(@NotNull Long dispositionId) {}
