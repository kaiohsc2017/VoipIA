package com.asteriskia.domain.insights;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * QualityScorecard — ficha de avaliação de qualidade cadastrada pelo administrador.
 * Só uma ficha pode estar ativa por vez (índice único parcial em is_active, V38);
 * editar uma ficha em uso cria uma nova versão em vez de sobrescrever a existente,
 * preservando o histórico de avaliações já feitas com a versão anterior.
 */
@Entity
@Table(name = "quality_scorecards")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QualityScorecard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = false;

    @Column(name = "version", nullable = false)
    @Builder.Default
    private Integer version = 1;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
