package com.asteriskia.domain.settings;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

/**
 * SettingsHistoryRepository — acesso à tabela settings_history.
 */
@Repository
public interface SettingsHistoryRepository extends JpaRepository<SettingsHistory, Long> {

    /** Retorna as N entradas mais recentes (ordenadas por data DESC). */
    List<SettingsHistory> findAllByOrderByChangedAtDesc(Pageable pageable);
}
