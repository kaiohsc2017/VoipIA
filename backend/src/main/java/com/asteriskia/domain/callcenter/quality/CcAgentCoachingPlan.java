package com.asteriskia.domain.callcenter.quality;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.insights.CallEvaluation;
import com.asteriskia.domain.insights.ScorecardItem;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * CcAgentCoachingPlan — Plano de Ação / Coaching vinculado a metas de qualidade do agente.
 */
@Entity
@Table(name = "cc_agent_coaching_plans")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcAgentCoachingPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id", nullable = false)
    private CcAgent agent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scorecard_item_id")
    private ScorecardItem scorecardItem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "evaluation_id")
    private CallEvaluation evaluation;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "action_items", columnDefinition = "JSONB")
    private String actionItems;

    @Column(name = "target_score", precision = 5, scale = 2)
    private BigDecimal targetScore;

    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private String status = "EM_ANDAMENTO";

    @Column(name = "deadline")
    private LocalDate deadline;

    @Column(name = "created_by", length = 100)
    @Builder.Default
    private String createdBy = "SYSTEM";

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
