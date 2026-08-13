package com.asteriskia.domain.callcenter;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcSettingRepository extends JpaRepository<CcSetting, Long> {

    Optional<CcSetting> findBySettingKey(String settingKey);
}
