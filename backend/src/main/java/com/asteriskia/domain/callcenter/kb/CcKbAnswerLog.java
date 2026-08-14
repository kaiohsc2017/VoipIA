package com.asteriskia.domain.callcenter.kb;

import com.asteriskia.domain.callcenter.chat.CcChatSession;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
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

/**
 * CcKbAnswerLog — registro de cada pergunta respondida (ou escalada) pelo nó
 * {@code consultar_base} (Fase 25). {@code matched=false} significa que nenhum trecho relevante
 * foi encontrado acima do limiar (escalou para fila humana, {@code costUsd=0}, nenhuma chamada ao
 * LLM); {@code matched=true} sempre teve uma chamada real ao Gemini e custo correspondente.
 * Alimenta o alerta de gasto {@code callcenter_autosservico} do Financeiro e a taxa de contenção
 * do bot.
 */
@Entity
@Table(name = "cc_kb_answer_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcKbAnswerLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private CcChatSession session;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(columnDefinition = "TEXT")
    private String answer;

    @Column(nullable = false)
    private Boolean matched;

    @Column(length = 60)
    private String model;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Builder.Default
    @Column(name = "cost_usd", nullable = false, precision = 10, scale = 6)
    private BigDecimal costUsd = BigDecimal.ZERO;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
