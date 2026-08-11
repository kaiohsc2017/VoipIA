package com.asteriskia.domain.financeiro;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** CostAlertConfigRequest — payload de atualização do limite de gasto de uma frente. */
public record CostAlertConfigRequest(
        @NotNull @DecimalMin("0") BigDecimal thresholdUsd, boolean enabled) {}
