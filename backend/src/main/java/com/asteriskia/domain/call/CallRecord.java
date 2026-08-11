package com.asteriskia.domain.call;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.List;
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

    @Column(name = "ura_id", nullable = false)
    private Integer uraId;

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

    @Column(name = "jira_resolution", length = 100)
    private String jiraResolution;

    @Column(name = "jira_last_synced_at")
    private LocalDateTime jiraLastSyncedAt;

    @Column(name = "subject_tag", length = 100)
    private String subjectTag;

    @Column(name = "audio_file_path", length = 500)
    private String audioFilePath;

    @Column(name = "call_type", length = 255)
    private String callType;

    @Column(name = "reported_ramal", length = 255)
    private String reportedRamal;

    @Column(name = "priority", length = 255)
    private String priority;

    @Column(name = "stt_tokens_in")
    @Builder.Default
    private Integer sttTokensIn = 0;

    @Column(name = "stt_tokens_out")
    @Builder.Default
    private Integer sttTokensOut = 0;

    @Column(name = "stt_model", length = 100)
    private String sttModel;

    @Column(name = "llm_tokens_in")
    @Builder.Default
    private Integer llmTokensIn = 0;

    @Column(name = "llm_tokens_out")
    @Builder.Default
    private Integer llmTokensOut = 0;

    @Column(name = "llm_model", length = 100)
    private String llmModel;

    @Column(name = "tts_tokens_in")
    @Builder.Default
    private Integer ttsTokensIn = 0;

    @Column(name = "tts_tokens_out")
    @Builder.Default
    private Integer ttsTokensOut = 0;

    @Column(name = "tts_model", length = 100)
    private String ttsModel;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** Respostas por pergunta, montadas em memória para o relatório — não persistido diretamente aqui. */
    @Transient
    @Builder.Default
    private List<AnswerView> answers = List.of();

    /** Uma resposta pronta para exibição no relatório: pergunta + valor. */
    public record AnswerView(Integer questionId, String questionText, String value) {}
}
