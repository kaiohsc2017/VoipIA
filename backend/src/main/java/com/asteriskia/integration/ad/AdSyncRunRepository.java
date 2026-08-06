package com.asteriskia.integration.ad;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdSyncRunRepository extends JpaRepository<AdSyncRun, Long> {
    Optional<AdSyncRun> findFirstByOrderByStartedAtDesc();
}
