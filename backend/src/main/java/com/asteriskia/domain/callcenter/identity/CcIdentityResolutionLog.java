package com.asteriskia.domain.callcenter.identity;

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

/**
 * CcIdentityResolutionLog — um registro por chamada de IA feita pela cascata de identificação
 * (Fase 14) — transcrição do login/nome falado e, quando aplicável, da confirmação falada.
 * Alimenta só o alerta de gasto {@code callcenter_identidade} do Financeiro; não guarda nenhum
 * dado do contato em si (nome/áudio) — só custo e desfecho agregado.
 */
@Entity
@Table(name = "cc_identity_resolution_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcIdentityResolutionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    @Column(name = "resolved_at", nullable = false)
    private LocalDateTime resolvedAt = LocalDateTime.now();

    /** "voice" ou "chat". */
    @Column(nullable = false, length = 10)
    private String channel;

    /** "resolved" | "unresolved" | "rejected" (cliente negou a confirmação falada). */
    @Column(nullable = false, length = 20)
    private String outcome;

    @Builder.Default
    @Column(name = "ai_cost_usd", nullable = false, precision = 12, scale = 6)
    private BigDecimal aiCostUsd = BigDecimal.ZERO;
}
