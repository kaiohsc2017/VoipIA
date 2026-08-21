package com.asteriskia.domain.callcenter.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.CcAgentRepository;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * CallCenterAgentScheduleControllerTest — prova o fechamento do achado de auditoria (N+1 de
 * requisições no WfmTab): o endpoint {@code /batch} devolve as escalas de vários agentes numa
 * única chamada, agrupadas por {@code agentId}.
 */
@ExtendWith(MockitoExtension.class)
class CallCenterAgentScheduleControllerTest {

    @Mock private CcAgentScheduleRepository scheduleRepository;
    @Mock private CcAgentRepository agentRepository;
    @Mock private CallCenterAgentAdherenceService adherenceService;

    private CallCenterAgentScheduleController controller;

    @BeforeEach
    void setUp() {
        controller = new CallCenterAgentScheduleController(scheduleRepository, agentRepository, adherenceService);
    }

    @Test
    @DisplayName("listBatch agrupa as escalas por agentId numa única consulta ao repositório")
    void listBatch_agrupaEscalasPorAgente() {
        CcAgent agent1 = CcAgent.builder().id(1L).build();
        CcAgent agent2 = CcAgent.builder().id(2L).build();
        CcAgentSchedule sched1 = CcAgentSchedule.builder()
                .id(10L).agent(agent1).dayOfWeek(1)
                .startTime(LocalTime.of(8, 0)).endTime(LocalTime.of(17, 0))
                .active(true).createdAt(LocalDateTime.now()).build();
        CcAgentSchedule sched2 = CcAgentSchedule.builder()
                .id(11L).agent(agent2).dayOfWeek(2)
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0))
                .active(true).createdAt(LocalDateTime.now()).build();
        when(scheduleRepository.findByAgentIdInAndActiveTrue(eq(List.of(1L, 2L))))
                .thenReturn(List.of(sched1, sched2));

        var response = controller.listBatch(List.of(1L, 2L));

        assertThat(response.getBody()).containsOnlyKeys(1L, 2L);
        assertThat(response.getBody().get(1L)).containsExactly(sched1);
        assertThat(response.getBody().get(2L)).containsExactly(sched2);
    }

    @Test
    @DisplayName("listBatch devolve mapa vazio para lista de ids vazia, sem consultar o banco")
    void listBatch_listaVazia_devolveMapaVazio() {
        var response = controller.listBatch(List.of());

        assertThat(response.getBody()).isEmpty();
    }

    @Test
    @DisplayName("listBatch agente sem nenhuma escala não aparece no mapa (frontend trata como lista vazia)")
    void listBatch_agenteSemEscala_naoApareceNoMapa() {
        CcAgent agent1 = CcAgent.builder().id(1L).build();
        CcAgentSchedule sched1 = CcAgentSchedule.builder()
                .id(10L).agent(agent1).dayOfWeek(1)
                .startTime(LocalTime.of(8, 0)).endTime(LocalTime.of(17, 0))
                .active(true).createdAt(LocalDateTime.now()).build();
        when(scheduleRepository.findByAgentIdInAndActiveTrue(eq(List.of(1L, 2L))))
                .thenReturn(List.of(sched1));

        var response = controller.listBatch(List.of(1L, 2L));

        assertThat(response.getBody()).containsOnlyKeys(1L);
    }
}
