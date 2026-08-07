package com.asteriskia.domain.callcenter;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface CcQueueRepository extends JpaRepository<CcQueue, Long>, JpaSpecificationExecutor<CcQueue> {
    Optional<CcQueue> findByName(String name);
}
