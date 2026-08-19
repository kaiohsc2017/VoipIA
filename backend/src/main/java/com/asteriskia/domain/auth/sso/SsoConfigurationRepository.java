package com.asteriskia.domain.auth.sso;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SsoConfigurationRepository extends JpaRepository<SsoConfiguration, Long> {
    Optional<SsoConfiguration> findByProviderNameIgnoreCase(String providerName);
}
