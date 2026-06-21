package com.asteriskia.domain.ai;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;

/**
 * Entrada de uma capability chain — define qual provedor/modelo usar
 * em qual prioridade para STT, LLM ou TTS.
 * priority=1 é o primário; 2, 3... são fallbacks em ordem.
 */
@Entity
@Table(name = "ai_capability_chain",
       uniqueConstraints = @UniqueConstraint(columnNames = {"capability","priority"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AiCapabilityChain {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** STT | LLM | TTS */
    @Column(length = 10, nullable = false)
    private String capability;

    /** 1 = primário, 2 = primeiro fallback … */
    @Column(nullable = false)
    private Integer priority;

    /** gemini | anthropic | openai | grok | perplexity | elevenlabs | local */
    @Column(length = 30, nullable = false)
    private String provider;

    /** ID exato do modelo conforme API do provedor */
    @Column(name = "model_id", length = 100, nullable = false)
    private String modelId;

    @Column(name = "is_enabled", nullable = false)
    private Boolean isEnabled;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;
}
