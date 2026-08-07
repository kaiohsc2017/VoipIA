package com.asteriskia.domain.callcenter.recording;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CallCenterRecordingRetentionController — configuração e disparo manual do expurgo de retenção
 * das gravações do Call Center. RBAC via {@code PERM_READ/WRITE_callcenter.gravacoes}
 * (mesmo resource_key da listagem/streaming — é a mesma tela).
 *
 * GET  /api/v1/callcenter/recordings/retention-config
 * PUT  /api/v1/callcenter/recordings/retention-config
 * POST /api/v1/callcenter/recordings/retention-config/run
 */
@RestController
@RequestMapping("/api/v1/callcenter/recordings/retention-config")
@RequiredArgsConstructor
public class CallCenterRecordingRetentionController {

    private final CallCenterRecordingRetentionService service;

    @GetMapping
    public ResponseEntity<RetentionConfigView> get() {
        return ResponseEntity.ok(service.getConfig());
    }

    @PutMapping
    public ResponseEntity<RetentionConfigView> update(
            @Valid @RequestBody RetentionConfigRequest request, Authentication auth) {
        return ResponseEntity.ok(service.updateConfig(request, auth.getName()));
    }

    @PostMapping("/run")
    public ResponseEntity<RetentionRunResult> runNow() {
        return ResponseEntity.ok(service.purgeExpired());
    }
}
