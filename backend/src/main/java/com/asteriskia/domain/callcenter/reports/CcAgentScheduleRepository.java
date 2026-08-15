package com.asteriskia.domain.callcenter.reports;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcAgentScheduleRepository extends JpaRepository<CcAgentSchedule, Long> {
    List<CcAgentSchedule> findByAgentIdAndActiveTrue(Long agentId);

    List<CcAgentSchedule> findByAgentIdAndDayOfWeekAndActiveTrue(Long agentId, Integer dayOfWeek);
}
