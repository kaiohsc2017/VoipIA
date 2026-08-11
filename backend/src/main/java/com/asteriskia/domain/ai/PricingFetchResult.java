package com.asteriskia.domain.ai;

import java.math.BigDecimal;

/**
 * PricingFetchResult — resultado de uma tentativa de buscar o preço atualizado de um modelo na
 * página pública de preços da Google. `success=false` nunca carrega valores — o chamador nunca
 * deve gravar preço a partir de um resultado com falha (ver {@link AiModelPricingSyncScheduler}).
 */
public record PricingFetchResult(
        String modelId,
        boolean success,
        BigDecimal pricePerMillionInputUsd,
        BigDecimal pricePerMillionOutputUsd,
        String failureReason) {

    public static PricingFetchResult ok(String modelId, BigDecimal input, BigDecimal output) {
        return new PricingFetchResult(modelId, true, input, output, null);
    }

    public static PricingFetchResult fail(String modelId, String reason) {
        return new PricingFetchResult(modelId, false, null, null, reason);
    }
}
