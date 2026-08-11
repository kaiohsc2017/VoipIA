package com.asteriskia.domain.callcenter.recording;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CallCenterRecordingIngestController — consumido pelo dialplan (CURL) de {@code _5XXX} em
 * {@code extensions.conf}. Não tem requestMatcher próprio em SecurityConfig — cai no
 * {@code anyRequest().authenticated()} genérico, protegido só pelo InternalKeyFilter
 * (X-Internal-Key), mesmo padrão de {@code UraRoutingController}.
 *
 * GET  /api/v1/internal/callcenter/queue-recording-config — resolve grava/aviso de uma fila
 * POST /api/v1/internal/callcenter/recordings             — registra a gravação ao fim da chamada
 */
@RestController
@RequestMapping("/api/v1/internal/callcenter")
@RequiredArgsConstructor
public class CallCenterRecordingIngestController {

    private final CallCenterRecordingService service;

    @GetMapping(value = "/queue-recording-config", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> queueRecordingConfig(@RequestParam String extension) {
        return ResponseEntity.ok(service.queueRecordingConfigText(extension));
    }

    @PostMapping("/recordings")
    public ResponseEntity<Void> register(
            @RequestParam String uniqueid,
            @RequestParam String extension,
            @RequestParam String filePath,
            @RequestParam(defaultValue = "false") boolean consentPlayed) {
        service.ingest(uniqueid, extension, filePath, consentPlayed);
        return ResponseEntity.ok().build();
    }
}
