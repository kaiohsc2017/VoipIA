package com.asteriskia.domain.callcenter.quality;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import org.hibernate.annotations.CreationTimestamp;

/**
 * CcQualityReportSnapshot — um ponto de evolução (nota geral ou de um item da ficha) gerado por
 * uma execução do relatório de qualidade (Fase 26), mesmo padrão de {@code AgentEvolutionSnapshot}
 * (V39). {@code itemId} nulo representa a nota total geral daquela execução.
 */
@Entity
@Table(name = "cc_quality_report_snapshots")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcQualityReportSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_id", nullable = false)
    private Long reportId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 10)
    private QualityReportScopeType scopeType;

    @Column(name = "scope_value", length = 200)
    private String scopeValue;

    @Column(name = "item_id")
    private Long itemId;

    @Column(name = "metric_key", nullable = false, length = 50)
    private String metricKey;

    @Column(nullable = false)
    private BigDecimal valor;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
