package com.asteriskia.domain.ai;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface AiCapabilityChainRepository extends JpaRepository<AiCapabilityChain, Long> {

    List<AiCapabilityChain> findByCapabilityOrderByPriorityAsc(String capability);

    @Modifying
    @Query("DELETE FROM AiCapabilityChain c WHERE c.capability = :capability")
    void deleteByCapability(String capability);
}
