package com.asteriskia.domain.callcenter.flow;

import com.asteriskia.domain.masterdata.BusinessUnit;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
 * CcFlow — metadado de um fluxo do Flow Builder (Fase 5a). {@link #entryExtension} é o ramal na
 * faixa 6000-6999, exclusiva do motor ARI/Stasis (Fase 5b) — nunca sobrepõe URAs (2XXX) ou filas
 * (5XXX). {@link #publishedVersionId} aponta para a {@code cc_flow_versions} atualmente
 * PUBLISHED; trocar esse ponteiro é o mecanismo tanto de publish quanto de rollback.
 */
@Entity
@Table(name = "cc_flows")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcFlow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 150)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String channel = "voice";

    @Column(name = "entry_extension", unique = true, length = 20)
    private String entryExtension;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "business_unit_id")
    private BusinessUnit businessUnit;

    @Builder.Default
    private Boolean active = true;

    @Column(name = "published_version_id")
    private Long publishedVersionId;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
