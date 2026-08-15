package com.asteriskia.domain.callcenter.copilot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CcContactProfileFeedback — feedback do agente (útil/não útil) sobre uma ação sugerida do
 * copiloto de IA (Fase 16.3) — matéria-prima para ajustar o prompt depois e sinal barato de que a
 * feature está entregando valor. {@code agentId} é só informativo (sem FK obrigatória): o
 * feedback nunca deixa de ser gravado por causa de um agente removido depois.
 */
@Entity
@Table(name = "cc_contact_profile_feedback")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcContactProfileFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "profile_id", nullable = false)
    private Long profileId;

    /** Índice da ação dentro de {@code acoesSugeridas}, na ordem em que o LLM as gerou. */
    @Column(name = "action_index", nullable = false)
    private Integer actionIndex;

    @Column(nullable = false)
    private Boolean useful;

    @Column(name = "agent_id")
    private Long agentId;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
