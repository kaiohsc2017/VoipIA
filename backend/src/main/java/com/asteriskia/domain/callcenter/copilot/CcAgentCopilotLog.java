package com.asteriskia.domain.callcenter.copilot;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.kb.CcKbArticle;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(name = "cc_agent_copilot_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcAgentCopilotLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id")
    private CcAgent agent;

    @Column(name = "interaction_id", length = 100)
    private String interactionId;

    @Column(name = "customer_utterance", nullable = false, columnDefinition = "TEXT")
    private String customerUtterance;

    @Column(name = "suggested_response", nullable = false, columnDefinition = "TEXT")
    private String suggestedResponse;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "suggested_kb_article_id")
    private CcKbArticle suggestedKbArticle;

    @Column(name = "confidence_score", nullable = false)
    @Builder.Default
    private Double confidenceScore = 0.0;

    @Column(name = "agent_feedback", length = 20)
    private String agentFeedback; // ACCEPTED, REJECTED, EDITED

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
