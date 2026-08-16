package com.asteriskia.config;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * HealthController — Endpoint público de health check.
 *
 * Usado por proxies reversos (Caddy), ferramentas de monitoração e
 * orquestradores externos para verificar a disponibilidade da API.
 *
 * Retorna HTTP 200 com status e timestamp para indicar que o serviço está ativo.
 * Não requer autenticação (configurado via SecurityConfig.permitAll).
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "voipia-backend",
                "timestamp", Instant.now().toString()
        ));
    }
}
