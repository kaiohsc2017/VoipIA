package com.asteriskia.domain.financeiro;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * FinanceiroCostAlertConfig — configuração do alerta de gasto em USD de uma frente do módulo
 * Financeiro ("ura", "insights" ou "envios"). Verificada diariamente por
 * {@link CostAlertScheduler}; {@code lastNotifiedMonth} evita repetir o alerta no mesmo mês.
 */
@Entity
@Table(name = "financeiro_cost_alerts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FinanceiroCostAlertConfig {

    @Id
    @Column(name = "scope", length = 20)
    private String scope;

    @Column(name = "threshold_usd", nullable = false)
    @Builder.Default
    private BigDecimal thresholdUsd = BigDecimal.ZERO;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = false;

    @Column(name = "last_notified_month", length = 7)
    private String lastNotifiedMonth;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;
}
