package com.asteriskia.domain.financeiro;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CostAlertController — configuração do alerta de gasto em USD por frente do módulo
 * Financeiro. Autorização por frente em SecurityConfig
 * (PERM_READ/WRITE_financeiro.{ura,insights,envios}).
 */
@RestController
@RequestMapping("/api/v1/financeiro/cost-alerts")
@RequiredArgsConstructor
public class CostAlertController {

    private final CostAlertService service;

    @GetMapping("/{scope}")
    public ResponseEntity<CostAlertConfigView> get(@PathVariable String scope) {
        return ResponseEntity.ok(service.getConfig(scope));
    }

    @PutMapping("/{scope}")
    public ResponseEntity<CostAlertConfigView> update(
            @PathVariable String scope,
            @Valid @RequestBody CostAlertConfigRequest request,
            Authentication auth) {
        return ResponseEntity.ok(service.updateConfig(scope, request, auth.getName()));
    }
}
