package com.asteriskia.domain.callcenter.ia;

import com.asteriskia.domain.callcenter.CcQueue;
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
import org.hibernate.annotations.UpdateTimestamp;

/**
 * CcIaAgent — configuração cadastrável de persona/prompt/modelo do nó "agente_ia" do Flow
 * Builder (Fase A do plano-mãe do Call Center). Reutilizável entre vários nós/fluxos — o nó do
 * grafo guarda só o id ({@code configuracaoIaId}), nunca o prompt embutido. {@code topK}/
 * {@code matchThreshold} nulos herdam os valores globais de {@code app.callcenter.kb.*} (mesma
 * base de conhecimento única da Fase 25, sem conceito de múltiplas bases).
 */
@Entity
@Table(name = "cc_ia_agents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcIaAgent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 120)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(name = "system_prompt", nullable = false, columnDefinition = "TEXT")
    private String systemPrompt;

    @Column(columnDefinition = "TEXT")
    private String greeting;

    @Column(nullable = false, length = 80)
    private String model;

    @Builder.Default
    @Column(nullable = false)
    private BigDecimal temperature = new BigDecimal("0.20");

    @Column(name = "top_k")
    private Integer topK;

    @Column(name = "match_threshold")
    private BigDecimal matchThreshold;

    @Column(name = "kb_tags", length = 500)
    private String kbTags;

    @Builder.Default
    @Column(name = "max_turns", nullable = false)
    private Integer maxTurns = 5;

    @Builder.Default
    @Column(name = "max_cost_usd", nullable = false)
    private BigDecimal maxCostUsd = new BigDecimal("0.10");

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fallback_queue_id")
    private CcQueue fallbackQueue;

    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
