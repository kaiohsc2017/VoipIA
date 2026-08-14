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
 * CcAgentSkill — vínculo agente↔skill com nível (Fase 5f.1). Tabela {@code cc_agent_skills}
 * existe desde a V48 (Fase 2); {@code level} foi acrescentado pela V76. Escala 1-5 (ver
 * comentário da V76): usada por {@link CallCenterSkillRoutingService} para decidir elegibilidade
 * de participação em fila — nunca para calcular prioridade manual (penalty).
 */
@Entity
@Table(name = "cc_agent_skills")
@IdClass(CcAgentSkillId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcAgentSkill {

    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id", nullable = false)
    private CcAgent agent;

    @Id
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "skill_id", nullable = false)
    private CcSkill skill;

    @Builder.Default
    private Integer level = 1;
}
