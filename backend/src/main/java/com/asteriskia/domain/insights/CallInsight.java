package com.asteriskia.domain.insights;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * CallInsight — resultado consolidado da análise de IA de uma chamada
 * (resumo, categoria, sentimento, aderência a script, criticidade).
 * insightsJson guarda o JSON completo retornado pelo LLM — CallInsightFinding
 * é a versão normalizada, usada para agregação no dashboard de tendências.
 */
@Entity
@Table(name = "call_insights")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CallInsight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "audio_file_id", nullable = false, unique = true)
    private Long audioFileId;

    @Column(name = "resumo", columnDefinition = "TEXT")
    private String resumo;

    @Column(name = "categoria_assunto", length = 100)
    private String categoriaAssunto;

    @Column(name = "sentimento_geral", length = 20)
    private String sentimentoGeral;

    @Column(name = "aderencia_script")
    private BigDecimal aderenciaScript;

    @Column(name = "criticidade", nullable = false, length = 10)
    @Builder.Default
    private String criticidade = "baixa";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "insights_json", nullable = false, columnDefinition = "jsonb")
    private String insightsJson;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
