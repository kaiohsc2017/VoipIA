package com.asteriskia.domain.ura;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * UraSettingsRepository — Acesso às mensagens configuráveis da URA.
 */
@Repository
public interface UraSettingsRepository extends JpaRepository<UraSettings, String> {
}
