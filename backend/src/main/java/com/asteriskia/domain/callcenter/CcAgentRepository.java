package com.asteriskia.domain.callcenter;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface CcAgentRepository extends JpaRepository<CcAgent, Long>, JpaSpecificationExecutor<CcAgent> {
    Optional<CcAgent> findByUserId(Integer userId);
}
