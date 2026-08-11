package com.asteriskia.domain.callcenter.supervision;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcQueueAlertConfigRepository extends JpaRepository<CcQueueAlertConfig, Long> {
    List<CcQueueAlertConfig> findByEnabledTrue();
}
