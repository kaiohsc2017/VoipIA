package com.asteriskia.domain.callcenter;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/** Chave composta (queue_id, skill_id) de {@link CcQueueSkill}. */
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class CcQueueSkillId implements Serializable {
    private Long queue;
    private Long skill;
}
