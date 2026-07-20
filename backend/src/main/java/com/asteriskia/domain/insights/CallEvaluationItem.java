package com.asteriskia.domain.insights;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * CallEvaluationItem — nota atribuída pela IA a um item específico da ficha
 * nesta chamada, com justificativa e trecho da transcrição que embasa a nota
 * (mesmo princípio de evidência ancorada de CallInsightFinding.trechoReferencia).
 * nota é sempre clampada em [0, ScorecardItem.notaMaxima] pelo backend antes de
 * persistir — o LLM pode devolver valor fora da escala.
 */
@Entity
@Table(name = "call_evaluation_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CallEvaluationItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "evaluation_id", nullable = false)
    private Long evaluationId;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "nota", nullable = false)
    private BigDecimal nota;

    @Column(name = "justificativa", columnDefinition = "TEXT")
    private String justificativa;

    @Column(name = "trecho_referencia", columnDefinition = "TEXT")
    private String trechoReferencia;
}
