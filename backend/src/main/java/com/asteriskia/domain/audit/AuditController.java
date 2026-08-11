package com.asteriskia.domain.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AuditController — Consulta do log de auditoria (Fase 13).
 *
 * GET /api/v1/audit             → lista paginada com filtros
 * GET /api/v1/audit/logins      → últimos eventos de login/falha
 * GET /api/v1/audit/actions     → lista de ações disponíveis
 */
@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditLogRepository repo;

    @GetMapping
        public ResponseEntity<Page<AuditLog>> list(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false)    String username,
            @RequestParam(required = false)    String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateTo) {

        return ResponseEntity.ok(
                repo.findAll(AuditLogSpecifications.withFilters(username, action, dateFrom, dateTo),
                        PageRequest.of(page, Math.min(size, 200))));
    }

    @GetMapping("/logins")
    public ResponseEntity<List<AuditLog>> logins(
            @RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(
                repo.findByActionInOrderByCreatedAtDesc(
                        List.of("LOGIN", "LOGIN_FAILED", "RATE_LIMIT_BLOCKED"),
                        PageRequest.of(0, Math.min(limit, 500))));
    }

    @GetMapping("/actions")
        public ResponseEntity<List<String>> actions() {
        return ResponseEntity.ok(List.of(
                "LOGIN", "LOGIN_FAILED", "SETTINGS_CHANGE",
                "USER_CREATE", "USER_UPDATE", "USER_DELETE",
                "MASTERDATA_CREATE", "MASTERDATA_UPDATE", "MASTERDATA_DELETE",
                "EXPORT", "RATE_LIMIT_BLOCKED",
                "TOTP_ENABLED", "TOTP_DISABLED", "TOTP_VERIFY_FAILED"
        ));
    }
}
