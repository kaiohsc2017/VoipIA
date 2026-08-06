package com.asteriskia.integration.ad;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;

/** AdSyncRun — auditoria de cada execução do job de sincronização com o AD. */
@Entity
@Table(name = "ad_sync_runs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdSyncRun {

    public enum Status {
        RUNNING,
        SUCCESS,
        FAILED,
        PARTIAL
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private Status status = Status.RUNNING;

    @Column(name = "users_synced", nullable = false)
    @Builder.Default
    private Integer usersSynced = 0;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;
}
