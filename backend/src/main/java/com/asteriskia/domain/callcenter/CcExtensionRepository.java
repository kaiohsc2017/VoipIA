package com.asteriskia.domain.callcenter;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcExtensionRepository extends JpaRepository<CcExtension, Long> {
    Optional<CcExtension> findByExtension(String extension);

    Optional<CcExtension> findByAgentId(Long agentId);
}
