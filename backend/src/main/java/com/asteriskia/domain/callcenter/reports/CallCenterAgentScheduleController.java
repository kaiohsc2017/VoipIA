package com.asteriskia.domain.callcenter.reports;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.CcAgentRepository;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * CallCenterAgentScheduleController — CRUD da escala do agente + aderência (Fase 9c.7). Sub-rota
 * de {@code /api/v1/callcenter/reports}, RBAC herdado do matcher genérico já existente
 * ({@code callcenter.reports}) — sem resource novo.
 */
@RestController
@RequestMapping("/api/v1/callcenter/reports/agent-schedules")
@RequiredArgsConstructor
public class CallCenterAgentScheduleController {

    private final CcAgentScheduleRepository scheduleRepository;
    private final CcAgentRepository agentRepository;
    private final CallCenterAgentAdherenceService adherenceService;

    public record ScheduleRequest(
            @NotNull Long agentId, @NotNull Integer dayOfWeek, @NotNull LocalTime startTime, @NotNull LocalTime endTime) {
    }

    @GetMapping
    public ResponseEntity<List<CcAgentSchedule>> list(@RequestParam Long agentId) {
        return ResponseEntity.ok(scheduleRepository.findByAgentIdAndActiveTrue(agentId));
    }

    /**
     * Busca em lote (achado de auditoria — o painel de escalas da equipe em {@code WfmTab.tsx}
     * fazia 1 requisição por agente via {@code Promise.all}, uma rajada de centenas de chamadas
     * HTTP simultâneas na escala de agentes já projetada pelo CLAUDE.md). Agrupa por
     * {@code agentId} para o frontend consumir direto no mesmo shape de state já usado.
     */
    @GetMapping("/batch")
    public ResponseEntity<Map<Long, List<CcAgentSchedule>>> listBatch(@RequestParam List<Long> agentIds) {
        if (agentIds.isEmpty()) {
            return ResponseEntity.ok(Map.of());
        }
        Map<Long, List<CcAgentSchedule>> byAgent = scheduleRepository.findByAgentIdInAndActiveTrue(agentIds).stream()
                .collect(Collectors.groupingBy(s -> s.getAgent().getId()));
        return ResponseEntity.ok(byAgent);
    }

    @PostMapping
    public ResponseEntity<CcAgentSchedule> create(@jakarta.validation.Valid @RequestBody ScheduleRequest request) {
        if (request.dayOfWeek() < 1 || request.dayOfWeek() > 7) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dayOfWeek deve estar entre 1 (segunda) e 7 (domingo).");
        }
        if (!request.endTime().isAfter(request.startTime())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Hora final deve ser depois da hora inicial.");
        }
        CcAgent agent = agentRepository.findById(request.agentId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agente não encontrado"));
        CcAgentSchedule schedule = CcAgentSchedule.builder()
                .agent(agent).dayOfWeek(request.dayOfWeek()).startTime(request.startTime()).endTime(request.endTime())
                .active(true).createdAt(LocalDateTime.now()).build();
        return ResponseEntity.ok(scheduleRepository.save(schedule));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        scheduleRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{agentId}/adherence")
    public ResponseEntity<List<AgentAdherenceRow>> adherence(
            @PathVariable Long agentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(adherenceService.adherence(agentId, from, to));
    }
}
