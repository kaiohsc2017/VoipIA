package com.asteriskia.domain.callcenter.reports;

import com.asteriskia.domain.callcenter.interaction.AgentState;
import com.asteriskia.domain.callcenter.interaction.CcAgentState;
import com.asteriskia.domain.callcenter.interaction.CcAgentStateRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CallCenterAgentAdherenceService — aderência à escala de um agente (sub-fase 9c.7 do plano
 * modulo-callcenter-omnicanal.plan.md). Reusa o mesmo algoritmo de recorte de período aberto/
 * cruzando fronteira já usado em {@code CallCenterAgentAggregationService} (Fase 9b) — aqui a
 * "janela do dia" vira a janela do TURNO ESCALADO, não o dia inteiro.
 *
 * <p>"Logado" = qualquer estado exceto {@link AgentState#OFFLINE} (mesma composição de
 * occupied+available+paused já usada na Fase 9b) dentro da janela do turno.
 */
@Service
@RequiredArgsConstructor
public class CallCenterAgentAdherenceService {

    private final CcAgentScheduleRepository scheduleRepository;
    private final CcAgentStateRepository agentStateRepository;

    @Transactional(readOnly = true)
    public List<AgentAdherenceRow> adherence(Long agentId, LocalDate from, LocalDate to) {
        List<AgentAdherenceRow> rows = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            rows.add(adherenceForDate(agentId, date));
        }
        return rows;
    }

    private AgentAdherenceRow adherenceForDate(Long agentId, LocalDate date) {
        int isoDayOfWeek = date.getDayOfWeek().getValue();
        List<CcAgentSchedule> shifts = scheduleRepository.findByAgentIdAndDayOfWeekAndActiveTrue(agentId, isoDayOfWeek);
        if (shifts.isEmpty()) {
            // Sem escala cadastrada pra este dia da semana — null, nunca 0 (D do plano 9c.7).
            return new AgentAdherenceRow(date, null, null, null);
        }

        long scheduledSeconds = 0;
        long loggedSeconds = 0;
        for (CcAgentSchedule shift : shifts) {
            LocalDateTime windowStart = LocalDateTime.of(date, shift.getStartTime());
            LocalDateTime windowEnd = LocalDateTime.of(date, shift.getEndTime());
            scheduledSeconds += Duration.between(windowStart, windowEnd).toSeconds();
            loggedSeconds += loggedSecondsInWindow(agentId, windowStart, windowEnd);
        }

        BigDecimal adherencePct = scheduledSeconds == 0
                ? null
                : BigDecimal.valueOf(Math.min(loggedSeconds, scheduledSeconds))
                        .divide(BigDecimal.valueOf(scheduledSeconds), 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(2, RoundingMode.HALF_UP);

        return new AgentAdherenceRow(date, scheduledSeconds, loggedSeconds, adherencePct);
    }

    /** Mesmo algoritmo de recorte de {@code CallCenterAgentAggregationService.secondsInEachState}
     * — um período de estado pode ter começado antes da janela, terminar depois, ou ainda estar
     * aberto ({@code endedAt} null = "vale até agora"). */
    private long loggedSecondsInWindow(Long agentId, LocalDateTime windowStart, LocalDateTime windowEnd) {
        LocalDateTime now = LocalDateTime.now();
        long total = 0;
        for (CcAgentState period : agentStateRepository.findOverlapping(agentId, windowStart, windowEnd)) {
            if (period.getState() == AgentState.OFFLINE) {
                continue;
            }
            LocalDateTime periodEnd = period.getEndedAt() != null ? period.getEndedAt() : now;
            LocalDateTime overlapStart = period.getStartedAt().isAfter(windowStart) ? period.getStartedAt() : windowStart;
            LocalDateTime overlapEnd = periodEnd.isBefore(windowEnd) ? periodEnd : windowEnd;
            if (overlapEnd.isAfter(overlapStart)) {
                total += Duration.between(overlapStart, overlapEnd).toSeconds();
            }
        }
        return total;
    }
}
