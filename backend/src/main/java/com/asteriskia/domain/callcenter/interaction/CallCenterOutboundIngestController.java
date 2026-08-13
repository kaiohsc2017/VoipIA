package com.asteriskia.domain.callcenter.interaction;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CallCenterOutboundIngestController — consumido pelo dialplan (CURL) do contexto {@code _X.} de
 * {@code ramais-internos} (Fase 23). Não tem requestMatcher próprio em SecurityConfig — cai no
 * {@code anyRequest().authenticated()} genérico, protegido só pelo InternalKeyFilter
 * (X-Internal-Key), mesmo padrão de {@code CallCenterRecordingIngestController}.
 *
 * POST /api/v1/internal/callcenter/outbound-start — chamada de saída iniciada (antes do Dial)
 * POST /api/v1/internal/callcenter/outbound-end   — chamada de saída encerrada (após o Dial)
 */
@RestController
@RequestMapping("/api/v1/internal/callcenter")
@RequiredArgsConstructor
public class CallCenterOutboundIngestController {

    private final CallCenterOutboundCallService service;

    @PostMapping("/outbound-start")
    public ResponseEntity<Void> start(
            @RequestParam String uniqueid,
            @RequestParam String extension,
            @RequestParam String dialed) {
        service.start(uniqueid, extension, dialed);
        return ResponseEntity.ok().build();
    }

    /** {@code answeredSeconds} chega como {@code String}, não {@code Integer}: o dialplan envia
     * {@code ${ANSWEREDTIME}}, que o Asterisk só preenche em chamada atendida — em
     * BUSY/NOANSWER/CANCEL/CONGESTION o parâmetro chega presente e VAZIO (não ausente), o que
     * faria o binding automático para {@code Integer} falhar com 400 antes de chegar ao service.
     * O parsing defensivo (vazio/inválido → não atendida) fica em
     * {@link CallCenterOutboundCallService}. */
    @PostMapping("/outbound-end")
    public ResponseEntity<Void> end(
            @RequestParam String uniqueid,
            @RequestParam String dialstatus,
            @RequestParam(required = false, defaultValue = "") String answeredSeconds) {
        service.end(uniqueid, dialstatus, answeredSeconds);
        return ResponseEntity.ok().build();
    }
}
