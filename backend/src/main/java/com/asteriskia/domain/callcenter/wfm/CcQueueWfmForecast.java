package com.asteriskia.domain.callcenter.wfm;

import com.asteriskia.domain.callcenter.CcQueue;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(name = "cc_queue_wfm_forecasts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcQueueWfmForecast {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "queue_id", nullable = false)
    private CcQueue queue;

    @Column(name = "forecast_timestamp", nullable = false)
    private Instant forecastTimestamp;

    @Column(name = "interval_minutes", nullable = false)
    @Builder.Default
    private Integer intervalMinutes = 15;

    @Column(name = "predicted_call_volume", nullable = false)
    private Integer predictedCallVolume;

    @Column(name = "predicted_aht_seconds", nullable = false)
    private Integer predictedAhtSeconds;

    @Column(name = "required_agents", nullable = false)
    private Integer requiredAgents;

    @Column(name = "current_scheduled_agents", nullable = false)
    @Builder.Default
    private Integer currentScheduledAgents = 0;

    @Column(name = "predicted_sla_percent", nullable = false)
    private Double predictedSlaPercent;

    @Column(name = "target_sla_percent", nullable = false)
    @Builder.Default
    private Double targetSlaPercent = 80.0;

    @Column(name = "sla_breach_risk", nullable = false)
    @Builder.Default
    private Boolean slaBreachRisk = false;

    @Column(name = "algorithm", nullable = false, length = 50)
    @Builder.Default
    private String algorithm = "ERLANG_C_EWMA";

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
