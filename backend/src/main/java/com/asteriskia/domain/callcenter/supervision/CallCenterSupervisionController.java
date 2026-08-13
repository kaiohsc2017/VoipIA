package com.asteriskia.domain.callcenter.supervision;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CallCenterSupervisionController — painel em tempo (quase) real e ações do supervisor sobre um
 * agente (Fase 6). RBAC via {@code PERM_READ_callcenter.supervisao}/
 * {@code PERM_WRITE_callcenter.supervisao}.
 *
 * <p>GET /api/v1/callcenter/supervision/snapshot — estatísticas de filas/agentes do dia POST
 * .../agents/{id}/listen|whisper|barge — origina chamada de monitoria via AMI (ChanSpy) POST
 * .../agents/{id}/force-pause|force-unpause — força estado do agente
 */
@RestController
@RequestMapping("/api/v1/callcenter/supervision")
@RequiredArgsConstructor
public class CallCenterSupervisionController {

    private final CallCenterSupervisionPanelService panelService;
    private final CallCenterSupervisionActionService actionService;

    @GetMapping("/snapshot")
    public ResponseEntity<SupervisionSnapshot> snapshot() {
        return ResponseEntity.ok(panelService.snapshot());
    }

    @PostMapping("/agents/{agentId}/listen")
    public ResponseEntity<Void> listen(@PathVariable Long agentId) {
        actionService.listen(agentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/agents/{agentId}/whisper")
    public ResponseEntity<Void> whisper(@PathVariable Long agentId) {
        actionService.whisper(agentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/agents/{agentId}/barge")
    public ResponseEntity<Void> barge(@PathVariable Long agentId) {
        actionService.barge(agentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/agents/{agentId}/force-pause")
    public ResponseEntity<Void> forcePause(
            @PathVariable Long agentId, @Valid @RequestBody ForcePauseRequest request) {
        actionService.forcePause(agentId, request.pauseReasonId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/agents/{agentId}/force-unpause")
    public ResponseEntity<Void> forceUnpause(@PathVariable Long agentId) {
        actionService.forceUnpause(agentId);
        return ResponseEntity.noContent().build();
    }

    /** Ação de "perfil específico" (Fase 15.3) — RBAC próprio {@code callcenter.supervisao.redirect},
     * separado de {@code callcenter.supervisao} (ver matcher dedicado em SecurityConfig, antes do
     * genérico de {@code /supervision/**}). */
    @PostMapping("/redirect/queue")
    public ResponseEntity<Void> redirectToQueue(@Valid @RequestBody RedirectQueueRequest request) {
        actionService.redirectToQueue(request.sourceQueueName(), request.channelUniqueId(), request.targetQueueId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/redirect/agent")
    public ResponseEntity<Void> redirectToAgent(@Valid @RequestBody RedirectAgentRequest request) {
        actionService.redirectToAgent(request.sourceQueueName(), request.channelUniqueId(), request.targetAgentId());
        return ResponseEntity.noContent().build();
    }
}
