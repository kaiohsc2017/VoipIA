package com.asteriskia.domain.callcenter.copilot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * CcContactProfile — perfil do contato traçado por IA (Fase 16.2), gerado de forma assíncrona a
 * partir do histórico unificado ({@link CallCenterContactHistoryService}) e reaproveitado por até
 * {@code app.callcenter.copiloto.cache-hours} (default 24h) antes de regerar — principal controle
 * de custo desta fase (dispara por contato, não por gravação, o pior perfil de custo do módulo).
 * {@code profileJson} é sempre o resultado JÁ validado/clampado contra {@link ContactProfileView}
 * — nunca a resposta crua do LLM (lição do overflow numérico da Fase 8).
 */
@Entity
@Table(name = "cc_contact_profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcContactProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "resolved_ad_sam", nullable = false, length = 128)
    private String resolvedAdSam;

    @Column(name = "interaction_id")
    private Long interactionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "profile_json", nullable = false, columnDefinition = "jsonb")
    private String profileJson;

    @Builder.Default
    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt = LocalDateTime.now();

    @Column(length = 60)
    private String model;

    @Builder.Default
    @Column(name = "input_tokens", nullable = false)
    private Integer inputTokens = 0;

    @Builder.Default
    @Column(name = "output_tokens", nullable = false)
    private Integer outputTokens = 0;

    @Builder.Default
    @Column(name = "cost_usd", nullable = false, precision = 12, scale = 6)
    private BigDecimal costUsd = BigDecimal.ZERO;
}
