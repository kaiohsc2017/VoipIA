package com.asteriskia.domain.insights;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * CallAudioFile — uma gravação (.wav+.xml) do call center corporativo Verint,
 * descoberta em /opt/audio pelo serviço asteriskia-insights.
 *
 * Módulo apartado do domínio Asterisk (call_records/uras) — sem FK para essas
 * tabelas. call_ref é a chave de correlação própria do Verint (prefixo
 * numérico do nome do arquivo = atributo x:ref do XML).
 */
@Entity
@Table(name = "call_audio_files")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CallAudioFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "call_ref", nullable = false, unique = true, length = 50)
    private String callRef;

    @Column(name = "wav_path", nullable = false, length = 500)
    private String wavPath;

    @Column(name = "xml_path", nullable = false, length = 500)
    private String xmlPath;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "call_starttime")
    private LocalDateTime callStarttime;

    @Column(name = "agent_name", length = 200)
    private String agentName;

    @Column(name = "agent_id_verint", length = 50)
    private String agentIdVerint;

    @Column(name = "extension", length = 20)
    private String extension;

    @Column(name = "ani", length = 50)
    private String ani;

    @Column(name = "dnis", length = 50)
    private String dnis;

    @Column(name = "direction", length = 10)
    private String direction;

    @Column(name = "skill", length = 200)
    private String skill;

    // Primeira coluna JSONB do projeto — sem precedente de Hibernate a espelhar
    // (ver relatório de padrões da Fase 3). @JdbcTypeCode(SqlTypes.JSON) é o
    // caminho nativo do Hibernate 6.x, sem dependência extra (hypersistence-utils).
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "xml_raw", columnDefinition = "jsonb")
    private String xmlRaw;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "pending";

    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    @Column(name = "ingested_at", insertable = false, updatable = false)
    private LocalDateTime ingestedAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

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
}
