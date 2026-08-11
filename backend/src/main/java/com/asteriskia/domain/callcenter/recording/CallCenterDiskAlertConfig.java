package com.asteriskia.domain.callcenter.recording;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * CallCenterDiskAlertConfig — configuração do alerta de disco do volume de gravações do Call
 * Center, linha única (id='default'). Granularidade diária (não mensal, diferente de
 * {@code FinanceiroCostAlertConfig}) — disco pode encher em poucos dias.
 */
@Entity
@Table(name = "cc_recording_disk_alert_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CallCenterDiskAlertConfig {

    @Id
    @Column(name = "id", length = 20)
    @Builder.Default
    private String id = "default";

    @Column(name = "threshold_percent", nullable = false)
    @Builder.Default
    private Integer thresholdPercent = 85;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "last_notified_date")
    private LocalDate lastNotifiedDate;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
