package com.asteriskia.domain.callcenter.businesshours;

import com.asteriskia.domain.callcenter.quality.CcHolidayRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BusinessHoursService — resolve se um calendário está aberto num instante (Fase 5e.1 do plano de
 * fechamento 5/7/9 do Call Center, V74). Consumido pelo nó {@code horario_funcionamento} do Flow
 * Builder, tanto no motor real ({@code FlowExecutionEngine}) quanto no simulador ({@code
 * FlowSimulationService}) — mesma lógica, sem duplicação, porque os dois despacham pelo mesmo
 * {@code NodeHandler}.
 *
 * <p><b>Precedência</b>: feriado primeiro (global ou específico do calendário), depois slot de
 * horário do dia da semana. <b>Fail-open</b>: calendário não informado (nó sem configuração) ou
 * inexistente nunca bloqueia a chamada — retorna {@link Status#ABERTO}, mesmo padrão de "nunca
 * travar a chamada por erro de configuração" já usado em outros nós do motor (ex.: áudio
 * inexistente em {@code MenuNodeHandler}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessHoursService {

    private final CcBusinessHoursRepository calendarRepository;
    private final CcBusinessHoursSlotRepository slotRepository;
    private final CcHolidayRepository holidayRepository;

    /** Resultado da checagem de horário de funcionamento. */
    public enum Status {
        ABERTO,
        FECHADO_HORARIO,
        FECHADO_FERIADO
    }

    @Transactional(readOnly = true)
    public Status isOpen(Long calendarId, Instant instant) {
        if (calendarId == null) {
            return Status.ABERTO;
        }
        var calendarOpt = calendarRepository.findById(calendarId);
        if (calendarOpt.isEmpty()) {
            log.warn("Calendário de horário de funcionamento {} não encontrado — considerando sempre aberto.", calendarId);
            return Status.ABERTO;
        }
        var calendar = calendarOpt.get();

        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(calendar.getTimezone());
        } catch (Exception e) {
            log.warn("Timezone inválida no calendário {} ({}) — usando America/Sao_Paulo.", calendarId, calendar.getTimezone());
            zoneId = ZoneId.of("America/Sao_Paulo");
        }
        var zoned = ZonedDateTime.ofInstant(instant, zoneId);
        var today = zoned.toLocalDate();

        var holidayDates = holidayRepository.findAllDatesForCalendar(calendarId);
        if (holidayDates.contains(today)) {
            return Status.FECHADO_FERIADO;
        }

        var slotsToday = slotRepository.findAllByCalendarIdAndDayOfWeek(calendarId, zoned.getDayOfWeek().getValue());
        var totalSlots = slotRepository.findAllByCalendarIdOrderByDayOfWeekAscStartTimeAsc(calendarId);
        if (totalSlots.isEmpty()) {
            // Calendário sem nenhum slot configurado ainda — nenhuma restrição definida, sempre aberto.
            return Status.ABERTO;
        }

        var horaAtual = zoned.toLocalTime();
        var dentroDeAlgumSlot =
                slotsToday.stream().anyMatch(slot -> !horaAtual.isBefore(slot.getStartTime()) && horaAtual.isBefore(slot.getEndTime()));
        return dentroDeAlgumSlot ? Status.ABERTO : Status.FECHADO_HORARIO;
    }
}
