package com.asteriskia.domain.ai;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Payload de atualização de preço de um modelo — usado pela tela Configurações → IA. */
public record AiModelPricingRequest(
        @NotNull @DecimalMin("0") BigDecimal pricePerMillionInputUsd,
        @NotNull @DecimalMin("0") BigDecimal pricePerMillionOutputUsd,
        // Só usado ao cadastrar preço de um model_id ainda não seedado — em modelos já
        // existentes o provider não é alterado por este request. Nulo/branco cai no
        // fallback "gemini" (único provedor em produção hoje — ver comentário da tabela
        // ai_model_pricing na migration V34).
        String provider) {}
