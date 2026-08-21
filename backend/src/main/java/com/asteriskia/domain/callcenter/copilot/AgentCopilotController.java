package com.asteriskia.domain.callcenter.copilot;

import com.asteriskia.domain.callcenter.interaction.CallCenterAgentStateService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/**
 * Proteção vem só dos matchers em {@code SecurityConfig} — não há
 * {@code @EnableMethodSecurity} configurado no projeto, então {@code @PreAuthorize} aqui seria
 * código morto (mesmo achado já corrigido em {@code SsoController}).
 *
 * <p><b>Escopo self-service:</b> {@code getHistory}/{@code processLiveTurn} sempre resolvem o
 * agente a partir do usuário autenticado ({@link CallCenterAgentStateService#currentAgent()},
 * mesmo padrão já usado em {@code CallCenterDesktopService}) — o {@code agentId} vindo do
 * chamador (path/corpo) é ignorado, a menos que o chamador tenha {@code ROLE_ADMIN}, caso em que
 * pode consultar qualquer agente informado. Fecha o IDOR encontrado na auditoria: antes, um
 * usuário com só {@code PERM_READ_callcenter.copilot} (concedido também aos grupos
 * "agente"/"atendente" pela migration V91) conseguia ler o histórico de QUALQUER agente trocando
 * o id na URL.
 */
@RestController
@RequestMapping("/api/v1/callcenter/copilot")
@RequiredArgsConstructor
public class AgentCopilotController {

    private final AgentCopilotService copilotService;
    private final CallCenterAgentStateService agentStateService;

    @PostMapping("/live-turn")
    public ResponseEntity<AgentCopilotService.CopilotSuggestionDto> processLiveTurn(
            @RequestBody LiveTurnRequest request) {
        Long agentId = resolveAgentId(request.agentId());
        var dto = copilotService.processLiveTurn(agentId, request.interactionId(), request.customerUtterance());
        return ResponseEntity.ok(dto);
    }

    @PostMapping("/feedback")
    public ResponseEntity<Void> registerFeedback(@RequestBody CopilotFeedbackRequest request) {
        boolean ok = copilotService.registerFeedback(request.logId(), request.feedback());
        if (ok) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }

    @GetMapping("/history/{agentId}")
    public ResponseEntity<List<AgentCopilotService.CopilotSuggestionDto>> getHistory(
            @PathVariable Long agentId) {
        return ResponseEntity.ok(copilotService.getHistoryForAgent(resolveAgentId(agentId)));
    }

    /**
     * Resolve o agente que a requisição pode consultar: o próprio agente autenticado sempre,
     * ou (só para {@code ROLE_ADMIN}) o {@code agentId} explicitamente informado pelo chamador.
     */
    private Long resolveAgentId(Long requestedAgentId) {
        if (isAdminCaller() && requestedAgentId != null) {
            return requestedAgentId;
        }
        return agentStateService.currentAgent().getId();
    }

    private boolean isAdminCaller() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null
                && auth.getAuthorities().stream()
                        .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    public record LiveTurnRequest(Long agentId, String interactionId, String customerUtterance) {}

    public record CopilotFeedbackRequest(Long logId, String feedback) {}
}
