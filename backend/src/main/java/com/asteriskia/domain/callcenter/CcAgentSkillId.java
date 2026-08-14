package com.asteriskia.domain.callcenter;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/** Chave composta (agent_id, skill_id) de {@link CcAgentSkill} — mesmo padrão de
 * AccessGroupPermissionId (chave composta simples via @IdClass). */
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class CcAgentSkillId implements Serializable {
    private Long agent;
    private Long skill;
}
