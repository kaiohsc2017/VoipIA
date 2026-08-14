package com.asteriskia.domain.callcenter.businesshours;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.quality.CcHolidayRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * BusinessHoursServiceTest — precedência feriado > horário, turno partido, timezone por
 * calendário e os dois cenários fail-open (calendário nulo/inexistente e calendário sem slot
 * nenhum configurado).
 */
@ExtendWith(MockitoExtension.class)
class BusinessHoursServiceTest {

    @Mock private CcBusinessHoursRepository calendarRepository;
    @Mock private CcBusinessHoursSlotRepository slotRepository;
    @Mock private CcHolidayRepository holidayRepository;

    private BusinessHoursService service;

    @BeforeEach
    void setUp() {
        service = new BusinessHoursService(calendarRepository, slotRepository, holidayRepository);
    }

    private CcBusinessHours calendar(String timezone) {
        return CcBusinessHours.builder().id(1L).name("Matriz").timezone(timezone).active(true).build();
    }

    private CcBusinessHoursSlot slot(int day, LocalTime start, LocalTime end) {
        return CcBusinessHoursSlot.builder().dayOfWeek(day).startTime(start).endTime(end).build();
    }

    /** Segunda-feira 10h em America/Sao_Paulo, convertido para Instant. */
    private Instant mondayTenAmSaoPaulo() {
        return ZonedDateTime.of(2026, 8, 17, 10, 0, 0, 0, ZoneId.of("America/Sao_Paulo")).toInstant();
    }

    @Test
    void calendarioNulo_semprAberto() {
        assertThat(service.isOpen(null, Instant.now())).isEqualTo(BusinessHoursService.Status.ABERTO);
    }

    @Test
    void calendarioInexistente_semprAberto_failOpen() {
        when(calendarRepository.findById(99L)).thenReturn(Optional.empty());

        assertThat(service.isOpen(99L, Instant.now())).isEqualTo(BusinessHoursService.Status.ABERTO);
    }

    @Test
    void calendarioSemNenhumSlot_semprAberto() {
        when(calendarRepository.findById(1L)).thenReturn(Optional.of(calendar("America/Sao_Paulo")));
        when(holidayRepository.findAllDatesForCalendar(1L)).thenReturn(Set.of());
        when(slotRepository.findAllByCalendarIdAndDayOfWeek(eq(1L), eq(1))).thenReturn(List.of());
        when(slotRepository.findAllByCalendarIdOrderByDayOfWeekAscStartTimeAsc(1L)).thenReturn(List.of());

        assertThat(service.isOpen(1L, mondayTenAmSaoPaulo())).isEqualTo(BusinessHoursService.Status.ABERTO);
    }

    @Test
    void dentroDoSlot_aberto() {
        when(calendarRepository.findById(1L)).thenReturn(Optional.of(calendar("America/Sao_Paulo")));
        when(holidayRepository.findAllDatesForCalendar(1L)).thenReturn(Set.of());
        var slots = List.of(slot(1, LocalTime.of(8, 0), LocalTime.of(12, 0)), slot(1, LocalTime.of(13, 0), LocalTime.of(18, 0)));
        when(slotRepository.findAllByCalendarIdAndDayOfWeek(1L, 1)).thenReturn(slots);
        when(slotRepository.findAllByCalendarIdOrderByDayOfWeekAscStartTimeAsc(1L)).thenReturn(slots);

        assertThat(service.isOpen(1L, mondayTenAmSaoPaulo())).isEqualTo(BusinessHoursService.Status.ABERTO);
    }

    @Test
    void turnoPartido_horarioDoAlmoco_fechadoPorHorario() {
        when(calendarRepository.findById(1L)).thenReturn(Optional.of(calendar("America/Sao_Paulo")));
        when(holidayRepository.findAllDatesForCalendar(1L)).thenReturn(Set.of());
        var slots = List.of(slot(1, LocalTime.of(8, 0), LocalTime.of(12, 0)), slot(1, LocalTime.of(13, 0), LocalTime.of(18, 0)));
        // Meio-dia e meia — fora dos dois turnos (turno partido).
        var meioDiaEMeia = ZonedDateTime.of(2026, 8, 17, 12, 30, 0, 0, ZoneId.of("America/Sao_Paulo")).toInstant();
        when(slotRepository.findAllByCalendarIdAndDayOfWeek(1L, 1)).thenReturn(slots);
        when(slotRepository.findAllByCalendarIdOrderByDayOfWeekAscStartTimeAsc(1L)).thenReturn(slots);

        assertThat(service.isOpen(1L, meioDiaEMeia)).isEqualTo(BusinessHoursService.Status.FECHADO_HORARIO);
    }

    @Test
    void foraDoSlot_fechadoPorHorario() {
        when(calendarRepository.findById(1L)).thenReturn(Optional.of(calendar("America/Sao_Paulo")));
        when(holidayRepository.findAllDatesForCalendar(1L)).thenReturn(Set.of());
        var slots = List.of(slot(1, LocalTime.of(8, 0), LocalTime.of(12, 0)));
        var noite = ZonedDateTime.of(2026, 8, 17, 22, 0, 0, 0, ZoneId.of("America/Sao_Paulo")).toInstant();
        when(slotRepository.findAllByCalendarIdAndDayOfWeek(1L, 1)).thenReturn(slots);
        when(slotRepository.findAllByCalendarIdOrderByDayOfWeekAscStartTimeAsc(1L)).thenReturn(slots);

        assertThat(service.isOpen(1L, noite)).isEqualTo(BusinessHoursService.Status.FECHADO_HORARIO);
    }

    @Test
    void feriado_tempPrecedenciaSobreHorario() {
        when(calendarRepository.findById(1L)).thenReturn(Optional.of(calendar("America/Sao_Paulo")));
        // Feriado bate com o dia do teste, mesmo dentro do slot de horário normal.
        when(holidayRepository.findAllDatesForCalendar(1L)).thenReturn(Set.of(LocalDate.of(2026, 8, 17)));

        assertThat(service.isOpen(1L, mondayTenAmSaoPaulo())).isEqualTo(BusinessHoursService.Status.FECHADO_FERIADO);
    }

    @Test
    void timezoneDiferente_afetaOResultado() {
        // 23h em UTC é ainda dia 17 às 20h em São Paulo (UTC-3) — mas em Tóquio (UTC+9) já é dia
        // 18, 08h. Configura o calendário em Tokyo e um slot só no dia 18 (terça).
        when(calendarRepository.findById(1L)).thenReturn(Optional.of(calendar("Asia/Tokyo")));
        var instant = ZonedDateTime.of(2026, 8, 17, 23, 0, 0, 0, ZoneId.of("UTC")).toInstant();
        when(holidayRepository.findAllDatesForCalendar(1L)).thenReturn(Set.of());
        var slots = List.of(slot(2, LocalTime.of(8, 0), LocalTime.of(18, 0))); // terça (2) em Tokyo
        lenient().when(slotRepository.findAllByCalendarIdAndDayOfWeek(1L, 2)).thenReturn(slots);
        when(slotRepository.findAllByCalendarIdOrderByDayOfWeekAscStartTimeAsc(1L)).thenReturn(slots);

        assertThat(service.isOpen(1L, instant)).isEqualTo(BusinessHoursService.Status.ABERTO);
    }

    @Test
    void timezoneInvalidaPersistida_naoQuebra_usaFallback() {
        when(calendarRepository.findById(1L)).thenReturn(Optional.of(calendar("Timezone/Invalida")));
        when(holidayRepository.findAllDatesForCalendar(1L)).thenReturn(Set.of());
        when(slotRepository.findAllByCalendarIdAndDayOfWeek(eq(1L), eq(1))).thenReturn(List.of());
        when(slotRepository.findAllByCalendarIdOrderByDayOfWeekAscStartTimeAsc(1L)).thenReturn(List.of());

        assertThat(service.isOpen(1L, mondayTenAmSaoPaulo())).isEqualTo(BusinessHoursService.Status.ABERTO);
    }
}
