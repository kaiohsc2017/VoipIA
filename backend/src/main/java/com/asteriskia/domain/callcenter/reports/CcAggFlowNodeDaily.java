package com.asteriskia.domain.callcenter.reports;

import com.asteriskia.domain.callcenter.flow.CcFlow;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CcAggFlowNodeDaily — abandono por nó de fluxo (sub-fase 9c.1). {@link #abandonedHere} conta as
 * execuções cujo {@code last_node_id} é este nó E cujo desfecho final é ABANDONED/ERROR (ou ainda
 * em aberto ao fim do dia) — ou seja, o nó onde a chamada morreu, não apenas por onde passou.
 */
@Entity
@Table(name = "cc_agg_flow_node_daily")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcAggFlowNodeDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "flow_id", nullable = false)
    private CcFlow flow;

    @Column(name = "node_id", nullable = false, length = 64)
    private String nodeId;

    @Column(name = "node_type", nullable = false, length = 40)
    private String nodeType;

    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Builder.Default
    @Column(nullable = false)
    private Integer entries = 0;

    @Builder.Default
    @Column(name = "abandoned_here", nullable = false)
    private Integer abandonedHere = 0;

    @Column(name = "computed_at", nullable = false)
    private LocalDateTime computedAt;
}
