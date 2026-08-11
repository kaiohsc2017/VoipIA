package com.asteriskia.domain.insights;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * CallTranscriptSegment — um turno de fala diarizado por locutor, dentro de
 * uma chamada (CallAudioFile). Diarização feita via LLM (áudio é mono, sem
 * canal separado) — ver stt_diarize.py no serviço de Insights.
 *
 * FK simples (audioFileId), sem relação JPA bidirecional — child é sempre
 * substituído por completo a cada (re)processamento da chamada (ver
 * InsightsIngestionService), não há navegação de grafo que justifique
 * @ManyToOne/@OneToMany aqui.
 */
@Entity
@Table(name = "call_transcript_segments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CallTranscriptSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "audio_file_id", nullable = false)
    private Long audioFileId;

    @Column(name = "speaker", nullable = false, length = 20)
    @Builder.Default
    private String speaker = "indefinido";

    @Column(name = "start_ms", nullable = false)
    private Integer startMs;

    @Column(name = "end_ms", nullable = false)
    private Integer endMs;

    @Column(name = "text", nullable = false, columnDefinition = "TEXT")
    private String text;

    @Column(name = "tone_acoustic", length = 20)
    private String toneAcoustic;

    @Column(name = "tone_semantic", length = 20)
    private String toneSemantic;

    @Column(name = "sentiment_score")
    private BigDecimal sentimentScore;
}
