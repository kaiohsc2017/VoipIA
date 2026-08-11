package com.asteriskia.domain.callcenter.supervision;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcSupervisionActionRepository extends JpaRepository<CcSupervisionAction, Long> {}
