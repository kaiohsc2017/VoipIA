package com.asteriskia.domain.insights;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * ScorecardItem — item/pergunta de uma ficha de avaliação, com peso na nota
 * ponderada e nota máxima possível. isCritical marca uma pergunta fatal/auto-fail:
 * se a chamada zerar este item, a avaliação inteira é reprovada independente da
 * nota total (padrão "compliance trigger" do mercado de QM).
 */
@Entity
@Table(name = "scorecard_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScorecardItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scorecard_id", nullable = false)
    private Long scorecardId;

    @Column(name = "ordem", nullable = false)
    private Integer ordem;

    @Column(name = "pergunta", nullable = false, columnDefinition = "TEXT")
    private String pergunta;

    @Column(name = "peso", nullable = false)
    @Builder.Default
    private BigDecimal peso = BigDecimal.ONE;

    @Column(name = "nota_maxima", nullable = false)
    @Builder.Default
    private Integer notaMaxima = 10;

    @Column(name = "is_critical", nullable = false)
    @Builder.Default
    private Boolean isCritical = false;
}
