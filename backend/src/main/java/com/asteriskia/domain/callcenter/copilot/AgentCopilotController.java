package com.asteriskia.domain.callcenter.copilot;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Proteção vem só dos matchers em {@code SecurityConfig} — não há
 * {@code @EnableMethodSecurity} configurado no projeto, então {@code @PreAuthorize} aqui seria
 * código morto (mesmo achado já corrigido em {@code SsoController}).
 *
 * <p>NOTA de auditoria: {@code getHistory}/{@code processLiveTurn} recebem {@code agentId} do
 * chamador sem validar contra o agente autenticado — um usuário com {@code PERM_READ_
 * callcenter.copilot} (concedido também ao grupo "agente"/"atendente" pela migration V91)
 * consegue ler o histórico de conversas/sugestões de QUALQUER agente trocando o id na URL
 * (padrão de IDOR já corrigido em outros endpoints do domínio callcenter, ex.: identidade do
 * contato na Fase 14). Não corrigido nesta passada — decisão de produto pendente sobre se este
 * endpoint é agent-facing (deveria sempre resolver o próprio agente) ou supervisor-facing
 * (deveria exigir uma permissão distinta de leitura de terceiros).
 */
@RestController
@RequestMapping("/api/v1/callcenter/copilot")
@RequiredArgsConstructor
public class AgentCopilotController {

    private final AgentCopilotService copilotService;

    @PostMapping("/live-turn")
    public ResponseEntity<AgentCopilotService.CopilotSuggestionDto> processLiveTurn(
            @RequestBody LiveTurnRequest request) {
        var dto = copilotService.processLiveTurn(request.agentId(), request.interactionId(), request.customerUtterance());
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
        return ResponseEntity.ok(copilotService.getHistoryForAgent(agentId));
    }

    public record LiveTurnRequest(Long agentId, String interactionId, String customerUtterance) {}

    public record CopilotFeedbackRequest(Long logId, String feedback) {}
}
