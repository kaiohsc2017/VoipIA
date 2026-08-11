package com.asteriskia.domain.ai;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;

/**
 * Armazena a API key de cada provedor de IA.
 * provider = gemini | anthropic | openai | grok | perplexity | elevenlabs | local
 */
@Entity
@Table(name = "ai_provider_keys")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AiProviderKey {

    @Id
    @Column(length = 30)
    private String provider;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String apiKey;

    @Column(nullable = false)
    private Boolean isActive;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;
}
