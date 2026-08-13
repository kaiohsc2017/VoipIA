package com.asteriskia.domain.callcenter;

import com.asteriskia.domain.callcenter.nps.CcSurvey;
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
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * CcQueue — Fila do Call Center (metadado próprio: BU/auditoria). O número da fila (campo
 * {@link #name}) é o mesmo ramal da faixa 5000-5999 e é espelhado na tabela ARA {@code queues}
 * (V46) por {@link CallCenterQueueService}.
 */
@Entity
@Table(name = "cc_queues")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcQueue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String name;

    @Column(name = "display_name", nullable = false, length = 150)
    private String displayName;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "business_unit_id")
    private BusinessUnit businessUnit;

    @Builder.Default
    private String strategy = "ringall";

    @Column(name = "timeout_seconds")
    @Builder.Default
    private Integer timeoutSeconds = 15;

    @Builder.Default
    private Boolean active = true;

    @Column(name = "recording_enabled", nullable = false)
    @Builder.Default
    private Boolean recordingEnabled = true;

    @Column(name = "consent_message_path")
    private String consentMessagePath;

    /** Pesquisa de satisfação desta fila (Fase 21) — nulo = sem pesquisa. O interruptor global
     * ({@code cc_settings.nps.enabled_globally}, Fase 19) sobrepõe mesmo com survey preenchida. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "survey_id")
    private CcSurvey survey;

    @Column(name = "nps_alert_enabled", nullable = false)
    @Builder.Default
    private Boolean npsAlertEnabled = false;

    @Column(name = "nps_alert_threshold")
    private Integer npsAlertThreshold;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
