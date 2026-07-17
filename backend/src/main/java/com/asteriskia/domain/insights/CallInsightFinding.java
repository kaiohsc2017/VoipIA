package com.asteriskia.domain.insights;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * CallInsightFinding — achado normalizado (melhoria/falha/treinamento/tendência)
 * extraído de CallInsight.insightsJson, ancorado num trecho da transcrição
 * quando possível. Existe para permitir agregação eficiente (contar/agrupar
 * por tipo e período) no dashboard sem parsear JSONB a cada consulta.
 */
@Entity
@Table(name = "call_insight_findings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CallInsightFinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "audio_file_id", nullable = false)
    private Long audioFileId;

    @Column(name = "tipo", nullable = false, length = 20)
    private String tipo;

    @Column(name = "descricao", nullable = false, columnDefinition = "TEXT")
    private String descricao;

    @Column(name = "trecho_referencia", columnDefinition = "TEXT")
    private String trechoReferencia;

    @Column(name = "prioridade", nullable = false, length = 10)
    @Builder.Default
    private String prioridade = "media";

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
