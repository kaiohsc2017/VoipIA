package com.asteriskia.domain.callcenter.supervision;

import jakarta.validation.constraints.NotNull;

/** ForcePauseRequest — motivo da pausa forçada pelo supervisor. */
public record ForcePauseRequest(@NotNull Long pauseReasonId) {}
