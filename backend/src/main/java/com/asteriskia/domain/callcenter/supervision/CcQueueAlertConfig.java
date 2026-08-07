package com.asteriskia.domain.callcenter.supervision;

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
 * CcQueueAlertConfig — limiar de SLA por fila (espera máxima e/ou nível de serviço mínimo) para
 * o alerta via Telegram (Fase 6). Granularidade diária, mesmo padrão do alerta de disco (V49).
 */
@Entity
@Table(name = "cc_queue_alert_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcQueueAlertConfig {

    @Id
    @Column(name = "queue_id")
    private Long queueId;

    @Column(name = "max_waiting_count")
    private Integer maxWaitingCount;

    @Column(name = "min_service_level_percent")
    private Integer minServiceLevelPercent;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = false;

    @Column(name = "last_notified_date")
    private LocalDate lastNotifiedDate;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
