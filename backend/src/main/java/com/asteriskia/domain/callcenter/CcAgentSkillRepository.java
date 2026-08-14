package com.asteriskia.domain.callcenter;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcAgentSkillRepository extends JpaRepository<CcAgentSkill, CcAgentSkillId> {
    List<CcAgentSkill> findByAgentId(Long agentId);

    Optional<CcAgentSkill> findByAgentIdAndSkillId(Long agentId, Long skillId);

    void deleteByAgentIdAndSkillId(Long agentId, Long skillId);
}
