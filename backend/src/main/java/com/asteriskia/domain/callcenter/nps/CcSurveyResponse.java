package com.asteriskia.domain.callcenter.nps;

import com.asteriskia.domain.callcenter.interaction.CcInteraction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/** CcSurveyResponse — resposta a uma {@link CcSurveyQuestion} dentro de uma interação (Fase 21). */
@Entity
@Table(name = "cc_survey_responses")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcSurveyResponse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "interaction_id", nullable = false)
    private CcInteraction interaction;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "question_id", nullable = false)
    private CcSurveyQuestion question;

    /** Nota por dígito (DTMF_*) ou classificação 0-{@code scaleMax} derivada da IA (FALADA_IA,
     * preenchida de forma assíncrona — nula enquanto pendente). */
    private Integer value;

    @Lob
    private String transcript;

    @Column(name = "audio_path")
    private String audioPath;

    @Column(name = "ai_cost_usd")
    private BigDecimal aiCostUsd;

    @Column(name = "skipped_reason", length = 60)
    private String skippedReason;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
