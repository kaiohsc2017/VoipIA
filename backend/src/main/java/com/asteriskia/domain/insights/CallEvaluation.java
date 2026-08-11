package com.asteriskia.domain.insights;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * CallEvaluation — resultado consolidado da avaliação de uma chamada contra uma
 * ficha (QualityScorecard). notaTotal é a soma ponderada normalizada em 0-100,
 * calculada deterministicamente pelo backend a partir das notas por item — nunca
 * aceita o total já pronto devolvido pelo LLM (mesma lição do clamp aplicado a
 * CallInsight.aderenciaScript). isFailed marca reprovação por regra fatal (algum
 * item is_critical=true recebeu nota 0), independente da notaTotal.
 */
@Entity
@Table(name = "call_evaluations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CallEvaluation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "audio_file_id", nullable = false, unique = true)
    private Long audioFileId;

    @Column(name = "scorecard_id", nullable = false)
    private Long scorecardId;

    @Column(name = "nota_total", nullable = false)
    private BigDecimal notaTotal;

    @Column(name = "is_failed", nullable = false)
    @Builder.Default
    private Boolean isFailed = false;

    @Column(name = "fail_reason", columnDefinition = "TEXT")
    private String failReason;

    @Column(name = "llm_tokens_in")
    @Builder.Default
    private Integer llmTokensIn = 0;

    @Column(name = "llm_tokens_out")
    @Builder.Default
    private Integer llmTokensOut = 0;

    @Column(name = "llm_model", length = 100)
    private String llmModel;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
