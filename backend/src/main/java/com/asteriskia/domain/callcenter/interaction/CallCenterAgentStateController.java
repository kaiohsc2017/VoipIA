package com.asteriskia.domain.callcenter.interaction;

import com.asteriskia.domain.callcenter.CcPauseReason;
import com.asteriskia.domain.callcenter.CcPauseReasonRepository;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CallCenterAgentStateController — estado/pausa do agente autenticado (Fase 4). RBAC via
 * {@code PERM_READ_callcenter.desktop}/{@code PERM_WRITE_callcenter.desktop}.
 *
 * <p>GET /api/v1/callcenter/agent-state/me — estado atual do agente autenticado GET
 * /api/v1/callcenter/agent-state/pause-reasons — catálogo de motivos de pausa ativos POST
 * /api/v1/callcenter/agent-state/me — transição de estado (Disponível/Pausa/Offline)
 */
@RestController
@RequestMapping("/api/v1/callcenter/agent-state")
@RequiredArgsConstructor
public class CallCenterAgentStateController {

    private final CallCenterAgentStateService service;
    private final CcPauseReasonRepository pauseReasonRepository;

    @GetMapping("/me")
    public ResponseEntity<AgentStateView> me() {
        return ResponseEntity.ok(service.currentState());
    }

    @GetMapping("/pause-reasons")
    public ResponseEntity<List<CcPauseReason>> pauseReasons() {
        return ResponseEntity.ok(pauseReasonRepository.findByActiveTrue());
    }

    @PostMapping("/me")
    public ResponseEntity<AgentStateView> setMyState(@Valid @RequestBody AgentStateRequest request) {
        return ResponseEntity.ok(service.setState(request));
    }
}
