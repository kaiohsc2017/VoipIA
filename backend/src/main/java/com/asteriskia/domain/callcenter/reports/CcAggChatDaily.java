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
 * CcAggChatDaily — agregado diário de chat (sub-fase 9c.2 do plano
 * modulo-callcenter-omnicanal.plan.md). Um registro por (queue, date), recalculado por upsert —
 * mesmo padrão de {@link CcAggQueueDaily}/{@link CcAggFlowDaily}.
 */
@Entity
@Table(name = "cc_agg_chat_daily")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcAggChatDaily {

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
    private Integer claimed = 0;

    @Builder.Default
    @Column(nullable = false)
    private Integer closed = 0;

    @Builder.Default
    @Column(name = "bot_contained", nullable = false)
    private Integer botContained = 0;

    @Builder.Default
    @Column(name = "bot_escalated", nullable = false)
    private Integer botEscalated = 0;

    @Column(name = "avg_frt_seconds")
    private BigDecimal avgFrtSeconds;

    @Column(name = "avg_response_seconds")
    private BigDecimal avgResponseSeconds;

    @Column(name = "avg_concurrent_chats")
    private BigDecimal avgConcurrentChats;

    @Column(name = "computed_at", nullable = false)
    private LocalDateTime computedAt;
}
