package com.asteriskia.domain.alert;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * AlertCall — Registro de uma ligação automática disparada por incidente Zabbix (Módulo 3).
 *
 * Status possíveis: PENDENTE | ATENDIDA | NAO_ATENDIDA | FALHA
 */
@Entity
@Table(name = "alert_calls")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AlertCall {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "call_date", nullable = false)
    private LocalDateTime callDate;

    @NotBlank
    @Column(name = "phone_number", nullable = false, length = 20)
    private String phoneNumber;

    @Column(name = "call_status", nullable = false, length = 20)
    @Builder.Default
    private String callStatus = "PENDENTE";

    @Column(name = "sip_response_code")
    private Integer sipResponseCode;

    @Column(name = "sip_response_reason", length = 100)
    private String sipResponseReason;

    @Column(name = "call_duration_secs")
    @Builder.Default
    private Integer callDurationSecs = 0;

    @NotBlank
    @Column(name = "zabbix_trigger_id", nullable = false, length = 50)
    private String zabbixTriggerId;

    @NotBlank
    @Column(name = "zabbix_incident_summary", nullable = false, columnDefinition = "TEXT")
    private String zabbixIncidentSummary;

    @Column(name = "zabbix_severity", length = 20)
    private String zabbixSeverity;

    @Column(name = "zabbix_host", length = 200)
    private String zabbixHost;

    @Column(name = "audio_file_path", length = 500)
    private String audioFilePath;

    @Column(name = "telegram_message_content", columnDefinition = "TEXT")
    private String telegramMessageContent;

    @Column(name = "telegram_sent_at")
    private LocalDateTime telegramSentAt;

    @Column(name = "asterisk_call_id", length = 100)
    private String asteriskCallId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
