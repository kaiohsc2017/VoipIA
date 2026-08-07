package com.asteriskia.domain.callcenter;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface CcAgentRepository extends JpaRepository<CcAgent, Long>, JpaSpecificationExecutor<CcAgent> {}
