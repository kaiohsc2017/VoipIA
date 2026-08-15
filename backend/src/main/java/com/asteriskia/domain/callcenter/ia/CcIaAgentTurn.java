package com.asteriskia.domain.callcenter.ia;

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
 * CcIaAgentTurn — log de uma rodada (pergunta→resposta) do laço do nó "agente_ia" (usado a partir
 * da Fase B). Sem FK para sessão de chat/canal de voz — {@code correlationRef} guarda o channelId
 * ARI ou {@code "chat-session-<id>"}, mesmo padrão de {@code ConsultarBaseNodeHandler}.
 */
@Entity
@Table(name = "cc_ia_agent_turns")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcIaAgentTurn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id", nullable = false)
    private CcIaAgent agent;

    @Column(nullable = false, length = 10)
    private String channel;

    @Column(name = "correlation_ref", length = 120)
    private String correlationRef;

    @Column(columnDefinition = "TEXT")
    private String question;

    @Column(columnDefinition = "TEXT")
    private String answer;

    @Builder.Default
    @Column(nullable = false)
    private Boolean matched = false;

    @Column(length = 80)
    private String model;

    @Builder.Default
    @Column(name = "input_tokens", nullable = false)
    private Integer inputTokens = 0;

    @Builder.Default
    @Column(name = "output_tokens", nullable = false)
    private Integer outputTokens = 0;

    @Builder.Default
    @Column(name = "cost_usd", nullable = false)
    private BigDecimal costUsd = BigDecimal.ZERO;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
