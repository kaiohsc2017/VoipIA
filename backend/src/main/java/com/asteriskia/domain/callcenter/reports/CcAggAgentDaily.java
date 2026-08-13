package com.asteriskia.domain.callcenter.reports;

import com.asteriskia.domain.callcenter.CcAgent;
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
 * CcAggAgentDaily — agregado diário de volume/ocupação de um agente de voz (sub-fase 9b do
 * plano modulo-callcenter-omnicanal.plan.md). Um registro por (agent, date) — recalculado
 * inteiro via upsert a cada reprocessamento, nunca acumulado incrementalmente (mesmo padrão de
 * {@code CcAggQueueDaily}, sub-fase 9a).
 */
@Entity
@Table(name = "cc_agg_agent_daily")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcAggAgentDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "agent_id", nullable = false)
    private CcAgent agent;

    @Column(name = "date", nullable = false)
    private LocalDate date;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "business_unit_id")
    private BusinessUnit businessUnit;

    @Builder.Default
    @Column(nullable = false)
    private Integer answered = 0;

    @Column(name = "avg_talk_seconds")
    private BigDecimal avgTalkSeconds;

    /** Chamadas OUTBOUND atendidas pelo destino neste dia (Fase 23) — answered/avgTalkSeconds
     * acima ficam restritos a INBOUND desde essa fase, para não misturar os dois sentidos. */
    @Builder.Default
    @Column(name = "outbound_placed", nullable = false)
    private Integer outboundPlaced = 0;

    @Column(name = "avg_outbound_talk_seconds")
    private BigDecimal avgOutboundTalkSeconds;

    @Builder.Default
    @Column(name = "occupied_seconds", nullable = false)
    private Integer occupiedSeconds = 0;

    @Builder.Default
    @Column(name = "available_seconds", nullable = false)
    private Integer availableSeconds = 0;

    @Builder.Default
    @Column(name = "paused_seconds", nullable = false)
    private Integer pausedSeconds = 0;

    @Builder.Default
    @Column(name = "offline_seconds", nullable = false)
    private Integer offlineSeconds = 0;

    @Column(name = "occupancy_pct")
    private BigDecimal occupancyPct;

    /** Média de {@code cc_interactions.nps_score} das interações deste agente no dia, com nota
     * (Fase 21) — nula se nenhuma foi pesquisada com nota ainda. */
    @Column(name = "avg_nps_score")
    private BigDecimal avgNpsScore;

    // Setado explicitamente pelo service a cada (re)cálculo — mesmo motivo de CcAggQueueDaily:
    // este registro é recalculado inteiro em cada upsert, não só criado uma vez.
    @Column(name = "computed_at", nullable = false)
    private LocalDateTime computedAt;
}
