package com.asteriskia.domain.callcenter.businesshours;

import com.asteriskia.domain.callcenter.businesshours.BusinessHoursDtos.CalendarRequest;
import com.asteriskia.domain.callcenter.businesshours.BusinessHoursDtos.CalendarView;
import com.asteriskia.domain.callcenter.businesshours.BusinessHoursDtos.SlotRequest;
import com.asteriskia.domain.callcenter.businesshours.BusinessHoursDtos.SlotView;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * CcBusinessHoursService — CRUD de calendário/slots de horário de funcionamento (Fase 5e.1, V74).
 * Lógica de "está aberto agora" fica em {@link BusinessHoursService} — esta classe só gerencia o
 * cadastro (mesma separação já usada entre {@code CallCenterFlowService} e {@code
 * FlowExecutionEngine}: uma classe monta o dado, outra o interpreta em tempo real).
 */
@Service
@RequiredArgsConstructor
public class CcBusinessHoursService {

    private final CcBusinessHoursRepository calendarRepository;
    private final CcBusinessHoursSlotRepository slotRepository;

    @Transactional(readOnly = true)
    public List<CalendarView> list() {
        return calendarRepository.findAllByOrderByNameAsc().stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public CalendarView getById(Long id) {
        return toView(findCalendarOrThrow(id));
    }

    @Transactional
    public CalendarView create(CalendarRequest request) {
        validateTimezone(request.timezone());
        var calendar =
                CcBusinessHours.builder()
                        .name(request.name().trim())
                        .timezone(request.timezone().trim())
                        .active(request.active())
                        .build();
        return toView(calendarRepository.save(calendar));
    }

    @Transactional
    public CalendarView update(Long id, CalendarRequest request) {
        validateTimezone(request.timezone());
        var calendar = findCalendarOrThrow(id);
        calendar.setName(request.name().trim());
        calendar.setTimezone(request.timezone().trim());
        calendar.setActive(request.active());
        return toView(calendarRepository.save(calendar));
    }

    @Transactional
    public void delete(Long id) {
        var calendar = findCalendarOrThrow(id);
        calendarRepository.delete(calendar);
    }

    @Transactional
    public SlotView addSlot(Long calendarId, SlotRequest request) {
        var calendar = findCalendarOrThrow(calendarId);
        validateSlotRequest(request);
        var slot =
                CcBusinessHoursSlot.builder()
                        .calendar(calendar)
                        .dayOfWeek(request.dayOfWeek())
                        .startTime(request.startTime())
                        .endTime(request.endTime())
                        .build();
        return SlotView.from(slotRepository.save(slot));
    }

    @Transactional
    public void removeSlot(Long calendarId, Long slotId) {
        // Confirma que o calendário existe e que o slot pertence a ele antes de remover — evita
        // que um id de slot de outro calendário seja apagado por engano via URL adivinhada.
        findCalendarOrThrow(calendarId);
        var slot =
                slotRepository
                        .findById(slotId)
                        .filter(s -> s.getCalendar().getId().equals(calendarId))
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Slot " + slotId + " não encontrado no calendário " + calendarId));
        slotRepository.delete(slot);
    }

    private CcBusinessHours findCalendarOrThrow(Long id) {
        return calendarRepository
                .findById(id)
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Calendário não encontrado: " + id));
    }

    private void validateTimezone(String timezone) {
        try {
            ZoneId.of(timezone.trim());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Timezone inválida: " + timezone);
        }
    }

    private void validateSlotRequest(SlotRequest request) {
        if (request.dayOfWeek() < 1 || request.dayOfWeek() > 7) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dia da semana deve estar entre 1 (segunda) e 7 (domingo).");
        }
        if (!request.endTime().isAfter(request.startTime())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Horário final deve ser maior que o inicial.");
        }
    }

    private CalendarView toView(CcBusinessHours calendar) {
        var slots = slotRepository.findAllByCalendarIdOrderByDayOfWeekAscStartTimeAsc(calendar.getId());
        return CalendarView.from(calendar, slots);
    }
}
