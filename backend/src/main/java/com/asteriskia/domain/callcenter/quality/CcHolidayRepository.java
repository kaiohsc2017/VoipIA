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

    /** Feriados que fecham um calendário específico: os globais (sem calendário) mais os
     * vinculados exatamente a esse calendário — usado por {@link
     * com.asteriskia.domain.callcenter.businesshours.BusinessHoursService#isOpen}. */
    @Query("SELECT h.holidayDate FROM CcHoliday h WHERE h.calendarId IS NULL OR h.calendarId = :calendarId")
    Set<LocalDate> findAllDatesForCalendar(Long calendarId);
}
