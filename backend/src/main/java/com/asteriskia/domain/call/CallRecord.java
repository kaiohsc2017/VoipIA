package com.asteriskia.domain.call;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * CallRecord — Registro de uma chamada recebida na URA (Módulo 1).
 */
@Entity
@Table(name = "call_records")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CallRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "call_uuid", nullable = false, unique = true)
    private UUID callUuid;

    @Column(name = "call_date", nullable = false)
    private LocalDateTime callDate;

    @Column(name = "call_duration_secs")
    @Builder.Default
    private Integer callDurationSecs = 0;

    @Column(name = "caller_number", nullable = false, length = 20)
    private String callerNumber;

    @Column(name = "client_name", length = 200)
    private String clientName;

    @Column(name = "transcription", columnDefinition = "TEXT")
    private String transcription;

    @Column(name = "jira_issue_key", length = 30)
    private String jiraIssueKey;

    @Column(name = "jira_issue_status", length = 50)
    @Builder.Default
    private String jiraIssueStatus = "Aberto";

    @Column(name = "audio_file_path", length = 500)
    private String audioFilePath;

    @Column(name = "call_type", length = 255)
    private String callType;

    @Column(name = "reported_ramal", length = 255)
    private String reportedRamal;

    @Column(name = "priority", length = 255)
    private String priority;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
