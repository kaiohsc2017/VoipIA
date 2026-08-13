package com.asteriskia.domain.callcenter.reports;

import com.asteriskia.domain.callcenter.CcQueue;
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
 * CcAggQueueDaily — agregado diário de volume/tempo/nível de serviço de uma fila de voz
 * (sub-fase 9a do plano modulo-callcenter-omnicanal.plan.md). Um registro por
 * (queue, date) — recalculado inteiro via upsert a cada reprocessamento, nunca acumulado
 * incrementalmente.
 */
@Entity
@Table(name = "cc_agg_queue_daily")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcAggQueueDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "queue_id", nullable = false)
    private CcQueue queue;

    @Column(name = "date", nullable = false)
    private LocalDate date;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "business_unit_id")
    private BusinessUnit businessUnit;

    @Builder.Default
    @Column(nullable = false)
    private Integer received = 0;

    @Builder.Default
    @Column(nullable = false)
    private Integer answered = 0;

    @Builder.Default
    @Column(nullable = false)
    private Integer abandoned = 0;

    @Column(name = "avg_wait_seconds")
    private BigDecimal avgWaitSeconds;

    @Column(name = "avg_talk_seconds")
    private BigDecimal avgTalkSeconds;

    @Column(name = "service_level_pct")
    private BigDecimal serviceLevelPct;

    /** Média de {@code cc_interactions.nps_score} das interações deste dia com nota (Fase 21) —
     * nula se nenhuma foi pesquisada com nota ainda. */
    @Column(name = "avg_nps_score")
    private BigDecimal avgNpsScore;

    // Setado explicitamente pelo service a cada (re)cálculo — não é @CreationTimestamp porque
    // este registro é recalculado inteiro em cada upsert (reprocessamento), não só criado uma vez.
    @Column(name = "computed_at", nullable = false)
    private LocalDateTime computedAt;
}
