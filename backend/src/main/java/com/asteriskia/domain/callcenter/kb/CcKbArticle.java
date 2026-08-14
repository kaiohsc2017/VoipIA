package com.asteriskia.domain.callcenter.kb;

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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * CcKbArticle — artigo da base de conhecimento própria do Call Center (Fase 25, D22).
 * {@code version} incrementa a cada edição (ver {@link CallCenterKbArticleService#update}) e é
 * comparado contra {@code indexedVersion} por {@code CallCenterKbIndexingScheduler} para decidir
 * o que reindexar — versionamento simples, sem histórico completo.
 */
@Entity
@Table(name = "cc_kb_articles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcKbArticle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(length = 500)
    private String tags;

    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;

    @Builder.Default
    @Column(nullable = false)
    private Integer version = 1;

    @Builder.Default
    @Column(name = "indexed_version", nullable = false)
    private Integer indexedVersion = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
