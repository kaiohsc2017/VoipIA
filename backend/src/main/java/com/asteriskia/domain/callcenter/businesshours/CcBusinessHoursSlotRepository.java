package com.asteriskia.domain.callcenter.businesshours;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CcBusinessHoursSlotRepository extends JpaRepository<CcBusinessHoursSlot, Long> {

    List<CcBusinessHoursSlot> findAllByCalendarIdOrderByDayOfWeekAscStartTimeAsc(Long calendarId);

    List<CcBusinessHoursSlot> findAllByCalendarIdAndDayOfWeek(Long calendarId, int dayOfWeek);
}
