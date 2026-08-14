package com.asteriskia.domain.callcenter.businesshours;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/** DTOs de request/view do CRUD de calendário de horário de funcionamento (Fase 5e.1, V74). */
public final class BusinessHoursDtos {

    private BusinessHoursDtos() {}

    public record CalendarRequest(@NotBlank String name, @NotBlank String timezone, boolean active) {}

    public record SlotRequest(
            @NotNull Integer dayOfWeek, @NotNull LocalTime startTime, @NotNull LocalTime endTime) {}

    public record SlotView(Long id, int dayOfWeek, LocalTime startTime, LocalTime endTime) {
        public static SlotView from(CcBusinessHoursSlot slot) {
            return new SlotView(slot.getId(), slot.getDayOfWeek(), slot.getStartTime(), slot.getEndTime());
        }
    }

    public record CalendarView(
            Long id, String name, String timezone, boolean active, LocalDateTime createdAt, List<SlotView> slots) {
        public static CalendarView from(CcBusinessHours calendar, List<CcBusinessHoursSlot> slots) {
            return new CalendarView(
                    calendar.getId(),
                    calendar.getName(),
                    calendar.getTimezone(),
                    calendar.isActive(),
                    calendar.getCreatedAt(),
                    slots.stream().map(SlotView::from).toList());
        }
    }
}
