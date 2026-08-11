package com.asteriskia.domain.callcenter.flow.engine;

import com.asteriskia.domain.callcenter.flow.CcFlow;
import com.asteriskia.domain.callcenter.flow.CcFlowVersion;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CcFlowExecution — uma chamada real que passou pelo app Stasis "callcenter" (Fase 5b).
 * {@link #flowVersion} é fixado no início ({@code StasisStart}) e nunca muda, mesmo que uma nova
 * versão seja publicada durante a chamada — é o que garante que uma chamada em curso não muda de
 * comportamento no meio (mesma invariante de imutabilidade da versão, vista da execução).
 */
@Entity
@Table(name = "cc_flow_executions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcFlowExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "flow_id", nullable = false)
    private CcFlow flow;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "flow_version_id", nullable = false)
    private CcFlowVersion flowVersion;

    @Column(name = "interaction_id")
    private Long interactionId;

    @Column(name = "channel_id", nullable = false, length = 80)
    private String channelId;

    @Column(name = "channel_unique_id", length = 80)
    private String channelUniqueId;

    @Builder.Default
    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt = LocalDateTime.now();

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(length = 30)
    private String outcome;

    @Column(name = "last_node_id", length = 64)
    private String lastNodeId;
}
