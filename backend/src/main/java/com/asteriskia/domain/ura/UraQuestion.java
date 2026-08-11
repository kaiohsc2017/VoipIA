package com.asteriskia.domain.ura;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * UraQuestion — Pergunta configurável da URA (Módulo 1).
 * Cada pergunta é lida via TTS e mapeada a um campo do Jira.
 */
@Entity
@Table(name = "ura_questions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UraQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "ura_id", nullable = false)
    private Integer uraId;

    @Column(name = "question_order", nullable = false)
    private Integer questionOrder;

    @Column(name = "question_text", nullable = false, length = 1000)
    private String questionText;

    @Column(name = "jira_field_key", nullable = false, length = 100)
    private String jiraFieldKey;

    /** Valores válidos separados por vírgula. Null = resposta livre. */
    @Column(name = "expected_values", length = 500)
    private String expectedValues;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
