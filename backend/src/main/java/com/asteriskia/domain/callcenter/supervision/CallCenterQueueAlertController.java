package com.asteriskia.domain.callcenter.supervision;

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
 * CallCenterQueueAlertController — configuração do limiar de SLA por fila (Fase 6). RBAC via
 * {@code PERM_READ_callcenter.supervisao}/{@code PERM_WRITE_callcenter.supervisao}.
 */
@RestController
@RequestMapping("/api/v1/callcenter/supervision/alert-config")
@RequiredArgsConstructor
public class CallCenterQueueAlertController {

    private final CallCenterSlaAlertService service;

    @GetMapping("/{queueId}")
    public ResponseEntity<QueueAlertConfigView> get(@PathVariable Long queueId) {
        return ResponseEntity.ok(service.getConfig(queueId));
    }

    @PutMapping("/{queueId}")
    public ResponseEntity<QueueAlertConfigView> update(
            @PathVariable Long queueId,
            @Valid @RequestBody QueueAlertConfigRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(service.updateConfig(queueId, request, authentication.getName()));
    }
}
