package com.asteriskia.domain.ura;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * UraSettingsRepository — Acesso às mensagens/configurações de cada URA.
 */
@Repository
public interface UraSettingsRepository extends JpaRepository<UraSettings, Long> {
    List<UraSettings> findByUraId(Integer uraId);
    Optional<UraSettings> findByUraIdAndKey(Integer uraId, String key);
}
