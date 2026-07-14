package com.asteriskia.domain.alert;

import jakarta.validation.constraints.NotBlank;

public record UpdateStatusRequest(@NotBlank String callStatus) {}
