package com.asteriskia.domain.callcenter.reports;

import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CallCenterProductivityController — "Produtividade do agente" (Fase 27). Sub-rota de
 * {@code /api/v1/callcenter/reports}, RBAC herdado do matcher genérico já existente em
 * {@code SecurityConfig} ({@code callcenter.reports}).
 */
@RestController
@RequestMapping("/api/v1/callcenter/reports/agent-productivity")
@RequiredArgsConstructor
public class CallCenterProductivityController {

    private final CallCenterProductivityService service;

    @GetMapping("/{agentId}")
    public ResponseEntity<AgentProductivityReport> get(
            @PathVariable Long agentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(service.build(agentId, from, to));
    }
}
