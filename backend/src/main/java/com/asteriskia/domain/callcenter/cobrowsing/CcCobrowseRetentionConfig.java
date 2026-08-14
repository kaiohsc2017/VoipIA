package com.asteriskia.domain.callcenter.cobrowsing;

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

/**
 * CcCobrowseRetentionConfig — configuração de retenção do co-browsing (Fase 17d), linha única
 * (id='default'), mesmo padrão de {@code CallCenterRecordingRetentionConfig} (voz). Tabela já
 * criada na migration V73 (17a) — sem migration nova nesta sub-fase.
 */
@Entity
@Table(name = "cc_cobrowse_retention_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcCobrowseRetentionConfig {

    @Id
    @Column(name = "id", length = 20)
    @Builder.Default
    private String id = "default";

    @Column(name = "retention_days", nullable = false)
    @Builder.Default
    private Integer retentionDays = 1826;

    @Column(name = "last_purge_at")
    private LocalDateTime lastPurgeAt;

    @Column(name = "last_purge_deleted_count")
    private Integer lastPurgeDeletedCount;

    @Column(name = "updated_by", length = 120)
    private String updatedBy;
}
