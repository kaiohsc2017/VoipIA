package com.asteriskia.domain.callcenter.reports;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcReportScheduleRepository extends JpaRepository<CcReportSchedule, Long> {
    List<CcReportSchedule> findByActiveTrue();
}
