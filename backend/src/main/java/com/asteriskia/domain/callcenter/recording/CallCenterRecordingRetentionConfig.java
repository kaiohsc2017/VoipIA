package com.asteriskia.domain.callcenter.recording;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * CallCenterRecordingRetentionConfig — configuração de retenção de gravações do Call Center,
 * linha única (id='default'), mesmo padrão de {@code FinanceiroCostAlertConfig}.
 */
@Entity
@Table(name = "cc_recording_retention_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CallCenterRecordingRetentionConfig {

    @Id
    @Column(name = "id", length = 20)
    @Builder.Default
    private String id = "default";

    @Column(name = "retention_days", nullable = false)
    @Builder.Default
    private Integer retentionDays = 1800;

    @Column(name = "last_purge_at")
    private LocalDateTime lastPurgeAt;

    @Column(name = "last_purge_deleted_count")
    private Integer lastPurgeDeletedCount;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
