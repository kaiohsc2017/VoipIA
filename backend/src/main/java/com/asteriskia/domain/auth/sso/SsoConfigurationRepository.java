package com.asteriskia.domain.auth.sso;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface SsoConfigurationRepository extends JpaRepository<SsoConfiguration, Long> {
    Optional<SsoConfiguration> findByProviderNameIgnoreCase(String providerName);

    // Consulta o valor bruto da coluna (fora do converter) para achar registros legados que
    // ainda não passaram pela cifragem de EncryptedSecretConverter.
    @Query(value = "SELECT id FROM sso_configurations WHERE client_secret IS NOT NULL "
            + "AND client_secret <> '' AND client_secret NOT LIKE 'enc:v1:%'", nativeQuery = true)
    List<Long> findIdsWithPlaintextClientSecret();
}
