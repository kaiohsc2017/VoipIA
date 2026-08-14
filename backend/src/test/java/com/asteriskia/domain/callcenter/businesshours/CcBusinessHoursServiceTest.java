package com.asteriskia.domain.callcenter.businesshours;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.businesshours.BusinessHoursDtos.CalendarRequest;
import com.asteriskia.domain.callcenter.businesshours.BusinessHoursDtos.SlotRequest;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/** CcBusinessHoursServiceTest — CRUD de calendário/slots (Fase 5e.1, V74). */
@ExtendWith(MockitoExtension.class)
class CcBusinessHoursServiceTest {

    @Mock private CcBusinessHoursRepository calendarRepository;
    @Mock private CcBusinessHoursSlotRepository slotRepository;

    private CcBusinessHoursService service;

    @BeforeEach
    void setUp() {
        service = new CcBusinessHoursService(calendarRepository, slotRepository);
    }

    @Test
    void create_timezoneValida_salvaCalendario() {
        var request = new CalendarRequest("Matriz", "America/Sao_Paulo", true);
        when(calendarRepository.save(any()))
                .thenAnswer(inv -> {
                    CcBusinessHours c = inv.getArgument(0);
                    c.setId(1L);
                    return c;
                });
        when(slotRepository.findAllByCalendarIdOrderByDayOfWeekAscStartTimeAsc(1L)).thenReturn(List.of());

        var view = service.create(request);

        assertThat(view.id()).isEqualTo(1L);
        assertThat(view.name()).isEqualTo("Matriz");
        assertThat(view.timezone()).isEqualTo("America/Sao_Paulo");
    }

    @Test
    void create_timezoneInvalida_rejeitaComBadRequest() {
        var request = new CalendarRequest("Matriz", "Nao/Existe", true);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Timezone inválida");
    }

    @Test
    void getById_inexistente_lanca404() {
        when(calendarRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(99L)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void addSlot_horarioFinalMenorQueInicial_rejeitado() {
        var calendar = CcBusinessHours.builder().id(1L).name("Matriz").timezone("America/Sao_Paulo").active(true).build();
        when(calendarRepository.findById(1L)).thenReturn(Optional.of(calendar));
        var request = new SlotRequest(1, LocalTime.of(18, 0), LocalTime.of(8, 0));

        assertThatThrownBy(() -> service.addSlot(1L, request)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void addSlot_diaForaDoIntervalo_rejeitado() {
        var calendar = CcBusinessHours.builder().id(1L).name("Matriz").timezone("America/Sao_Paulo").active(true).build();
        when(calendarRepository.findById(1L)).thenReturn(Optional.of(calendar));
        var request = new SlotRequest(8, LocalTime.of(8, 0), LocalTime.of(18, 0));

        assertThatThrownBy(() -> service.addSlot(1L, request)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void addSlot_valido_salvaEDelegaAoCalendario() {
        var calendar = CcBusinessHours.builder().id(1L).name("Matriz").timezone("America/Sao_Paulo").active(true).build();
        when(calendarRepository.findById(1L)).thenReturn(Optional.of(calendar));
        var request = new SlotRequest(1, LocalTime.of(8, 0), LocalTime.of(12, 0));
        when(slotRepository.save(any()))
                .thenAnswer(inv -> {
                    CcBusinessHoursSlot s = inv.getArgument(0);
                    s.setId(10L);
                    return s;
                });

        var view = service.addSlot(1L, request);

        assertThat(view.id()).isEqualTo(10L);
        assertThat(view.dayOfWeek()).isEqualTo(1);
    }

    @Test
    void removeSlot_slotDeOutroCalendario_lanca404() {
        var calendar = CcBusinessHours.builder().id(1L).name("Matriz").build();
        var outroCalendario = CcBusinessHours.builder().id(2L).name("Outro").build();
        when(calendarRepository.findById(1L)).thenReturn(Optional.of(calendar));
        var slot = CcBusinessHoursSlot.builder().id(10L).calendar(outroCalendario).build();
        lenient().when(slotRepository.findById(10L)).thenReturn(Optional.of(slot));

        assertThatThrownBy(() -> service.removeSlot(1L, 10L)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void delete_delegaAoRepository() {
        var calendar = CcBusinessHours.builder().id(1L).name("Matriz").build();
        when(calendarRepository.findById(1L)).thenReturn(Optional.of(calendar));

        service.delete(1L);

        verify(calendarRepository).delete(calendar);
    }
}
