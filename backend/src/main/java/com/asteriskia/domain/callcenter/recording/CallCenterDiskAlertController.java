package com.asteriskia.domain.callcenter.recording;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CallCenterDiskAlertController — configuração do alerta de disco do volume de gravações do Call
 * Center. RBAC via {@code PERM_READ/WRITE_callcenter.gravacoes} (mesma tela de Gravações).
 *
 * GET /api/v1/callcenter/recordings/disk-alert-config
 * PUT /api/v1/callcenter/recordings/disk-alert-config
 */
@RestController
@RequestMapping("/api/v1/callcenter/recordings/disk-alert-config")
@RequiredArgsConstructor
public class CallCenterDiskAlertController {

    private final CallCenterDiskAlertService service;

    @GetMapping
    public ResponseEntity<DiskAlertConfigView> get() {
        return ResponseEntity.ok(service.getConfig());
    }

    @PutMapping
    public ResponseEntity<DiskAlertConfigView> update(
            @Valid @RequestBody DiskAlertConfigRequest request, Authentication auth) {
        return ResponseEntity.ok(service.updateConfig(request, auth.getName()));
    }
}
