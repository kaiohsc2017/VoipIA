package com.asteriskia.domain.callcenter.reports;

import com.asteriskia.domain.callcenter.flow.CcFlow;
import com.asteriskia.domain.masterdata.BusinessUnit;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CcAggFlowDaily — agregado diário de volume/desfecho de execuções de fluxo visual (sub-fase 9c.1
 * do plano modulo-callcenter-omnicanal.plan.md). Um registro por (flow, date), recalculado por
 * upsert a cada reprocessamento — mesmo padrão de {@link CcAggQueueDaily}/{@link CcAggAgentDaily}.
 */
@Entity
@Table(name = "cc_agg_flow_daily")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcAggFlowDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "flow_id", nullable = false)
    private CcFlow flow;

    @Column(name = "date", nullable = false)
    private LocalDate date;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "business_unit_id")
    private BusinessUnit businessUnit;

    @Builder.Default
    @Column(nullable = false)
    private Integer executions = 0;

    @Builder.Default
    @Column(nullable = false)
    private Integer completed = 0;

    @Builder.Default
    @Column(name = "transferred_queue", nullable = false)
    private Integer transferredQueue = 0;

    @Builder.Default
    @Column(name = "transferred_extension", nullable = false)
    private Integer transferredExtension = 0;

    @Builder.Default
    @Column(nullable = false)
    private Integer abandoned = 0;

    @Builder.Default
    @Column(nullable = false)
    private Integer errored = 0;

    @Column(name = "avg_duration_seconds")
    private BigDecimal avgDurationSeconds;

    @Column(name = "computed_at", nullable = false)
    private LocalDateTime computedAt;
}
