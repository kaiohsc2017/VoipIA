package com.asteriskia.domain.callcenter.reports;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.CcAgentRepository;
import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.CcQueueRepository;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * CallCenterReportScheduleController — CRUD do agendamento de exportação de relatório (Fase
 * 9c.6). Sub-rota de {@code /api/v1/callcenter/reports}, RBAC herdado do matcher genérico já
 * existente ({@code callcenter.reports}) — sem resource novo.
 */
@RestController
@RequestMapping("/api/v1/callcenter/reports/schedules")
@RequiredArgsConstructor
public class CallCenterReportScheduleController {

    private final CallCenterReportScheduleService scheduleService;
    private final CcQueueRepository queueRepository;
    private final CcAgentRepository agentRepository;

    public record ScheduleRequest(
            @NotBlank String name,
            @NotBlank String reportType,
            Long queueId,
            Long agentId,
            Integer periodDays,
            @NotBlank String frequency,
            Integer dayOfWeek,
            Integer dayOfMonth,
            Integer hourOfDay,
            @NotBlank String channel,
            @NotBlank String recipient) {
    }

    @GetMapping
    public ResponseEntity<List<CcReportSchedule>> list() {
        return ResponseEntity.ok(scheduleService.list());
    }

    @PostMapping
    public ResponseEntity<CcReportSchedule> create(
            @jakarta.validation.Valid @RequestBody ScheduleRequest request, Authentication authentication) {
        CcQueue queue = request.queueId() != null ? findQueue(request.queueId()) : null;
        CcAgent agent = request.agentId() != null ? findAgent(request.agentId()) : null;
        CcReportSchedule schedule = CcReportSchedule.builder()
                .name(request.name())
                .reportType(request.reportType())
                .queue(queue)
                .agent(agent)
                .periodDays(request.periodDays() != null ? request.periodDays() : 7)
                .frequency(request.frequency())
                .dayOfWeek(request.dayOfWeek())
                .dayOfMonth(request.dayOfMonth())
                .hourOfDay(request.hourOfDay() != null ? request.hourOfDay() : 8)
                .channel(request.channel())
                .recipient(request.recipient())
                .active(true)
                .build();
        String createdBy = authentication != null ? authentication.getName() : "admin";
        return ResponseEntity.ok(scheduleService.create(schedule, createdBy));
    }

    @PutMapping("/{id}/active")
    public ResponseEntity<Void> setActive(@PathVariable Long id, @RequestBody java.util.Map<String, Boolean> body) {
        scheduleService.setActive(id, Boolean.TRUE.equals(body.get("active")));
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        scheduleService.delete(id);
        return ResponseEntity.ok().build();
    }

    private CcQueue findQueue(Long id) {
        return queueRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Fila não encontrada"));
    }

    private CcAgent findAgent(Long id) {
        return agentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Agente não encontrado"));
    }
}
