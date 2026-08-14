package com.asteriskia.domain.callcenter.quality;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface CcHolidayRepository extends JpaRepository<CcHoliday, Long> {

    List<CcHoliday> findAllByOrderByHolidayDateAsc();

    @Query("SELECT h.holidayDate FROM CcHoliday h")
    Set<LocalDate> findAllDates();
}
