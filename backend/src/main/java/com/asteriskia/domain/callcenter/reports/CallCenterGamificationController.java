package com.asteriskia.domain.callcenter.reports;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import lombok.RequiredArgsConstructor;

/**
 * CallCenterGamificationController — ranking de agentes por NPS médio (Fase 27 do plano
 * callcenter-parte-iii-revisado). Sub-rota de {@code /api/v1/callcenter/reports}, RBAC herdado
 * do matcher genérico já existente em {@code SecurityConfig} ({@code callcenter.reports}) — sem
 * matcher próprio necessário.
 */
@RestController
@RequestMapping("/api/v1/callcenter/reports/gamification")
@RequiredArgsConstructor
public class CallCenterGamificationController {

    private final CallCenterGamificationService service;

    @GetMapping
    public ResponseEntity<GamificationReport> rank(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer minCalls) {
        return ResponseEntity.ok(service.rank(from, to, minCalls));
    }
}
