package com.asteriskia.integration.ad;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdGroupMappingRepository extends JpaRepository<AdGroupMapping, Long> {
    Optional<AdGroupMapping> findByAdGroupName(String adGroupName);
}
