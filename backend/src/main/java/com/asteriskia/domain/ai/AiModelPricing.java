package com.asteriskia.domain.ai;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * AiModelPricing — preço por milhão de tokens (entrada/saída) de um modelo de IA, usado para
 * estimar o custo de cada chamada (Módulo 1 → aba Custos IA). Chave por model_id (não por
 * provider+model_id) — ver comentário da tabela na migration V34.
 */
@Entity
@Table(name = "ai_model_pricing")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiModelPricing {

    @Id
    @Column(name = "model_id", length = 100)
    private String modelId;

    @Column(name = "provider", length = 30, nullable = false)
    private String provider;

    @Column(name = "price_per_million_input_usd", nullable = false)
    @Builder.Default
    private BigDecimal pricePerMillionInputUsd = BigDecimal.ZERO;

    @Column(name = "price_per_million_output_usd", nullable = false)
    @Builder.Default
    private BigDecimal pricePerMillionOutputUsd = BigDecimal.ZERO;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;
}
