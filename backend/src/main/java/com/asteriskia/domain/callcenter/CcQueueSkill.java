package com.asteriskia.domain.callcenter;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CcQueueSkill — skill exigida por uma fila, com nível mínimo (Fase 5f.1). Tabela
 * {@code cc_queue_skills} existe desde a V48 (Fase 2); {@code minLevel} foi acrescentado pela
 * V76. Um agente só é elegível para a fila se atingir {@code minLevel} em TODAS as skills
 * exigidas por ela — ver {@link CallCenterSkillRoutingService#isEligible}.
 */
@Entity
@Table(name = "cc_queue_skills")
@IdClass(CcQueueSkillId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcQueueSkill {

    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "queue_id", nullable = false)
    private CcQueue queue;

    @Id
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "skill_id", nullable = false)
    private CcSkill skill;

    @Builder.Default
    @jakarta.persistence.Column(name = "min_level")
    private Integer minLevel = 1;
}
