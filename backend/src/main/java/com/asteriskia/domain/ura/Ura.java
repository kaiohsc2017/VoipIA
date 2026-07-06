package com.asteriskia.domain.ura;

import com.asteriskia.domain.masterdata.BusinessUnit;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Ura — URA configurável (Módulo 1): ramal, perguntas e integração com Jira próprios.
 */
@Entity
@Table(name = "uras")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Ura {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, unique = true, length = 20)
    private String extension;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(name = "jira_integration_enabled", nullable = false)
    @Builder.Default
    private Boolean jiraIntegrationEnabled = true;

    /**
     * BU de origem da URA — usada para escopar Chamadas (call_records) por BU
     * do usuário logado. Opcional: URAs sem BU (ex.: a legada id=1) só
     * aparecem para ADMIN quando o controle de acesso por BU está ativo.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "business_unit_id")
    private BusinessUnit businessUnit;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
