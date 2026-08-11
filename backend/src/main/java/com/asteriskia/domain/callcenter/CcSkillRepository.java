package com.asteriskia.domain.callcenter;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcSkillRepository extends JpaRepository<CcSkill, Long> {}
