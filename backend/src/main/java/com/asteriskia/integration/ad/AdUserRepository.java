package com.asteriskia.integration.ad;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdUserRepository extends JpaRepository<AdUser, Long> {
    Optional<AdUser> findBySamAccountName(String samAccountName);
}
