package com.asteriskia.domain.callcenter.recording;

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
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * CcRecording — registro de uma gravação MixMonitor de uma chamada de fila do Call Center
 * (Fase 3). Sem FK para uma interação formal ainda ({@code cc_interactions} chega na Fase 4) —
 * o vínculo hoje é só por {@link #queue}/{@link #queueExtension}/{@link #channelUniqueId}.
 */
@Entity
@Table(name = "cc_recordings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcRecording {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "queue_id")
    private CcQueue queue;

    @Column(name = "queue_extension", nullable = false, length = 10)
    private String queueExtension;

    @Column(name = "channel_uniqueid", nullable = false, unique = true, length = 64)
    private String channelUniqueId;

    @Column(name = "file_path", nullable = false, length = 255)
    private String filePath;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "business_unit_id")
    private BusinessUnit businessUnit;

    @Column(name = "consent_played", nullable = false)
    @Builder.Default
    private Boolean consentPlayed = false;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
