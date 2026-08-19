package com.asteriskia.domain.callcenter.copilot;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/callcenter/copilot")
@RequiredArgsConstructor
public class AgentCopilotController {

    private final AgentCopilotService copilotService;

    @PostMapping("/live-turn")
    @PreAuthorize("hasAuthority('callcenter.copilot:write') or hasRole('ADMIN')")
    public ResponseEntity<AgentCopilotService.CopilotSuggestionDto> processLiveTurn(
            @RequestBody LiveTurnRequest request) {
        var dto = copilotService.processLiveTurn(request.agentId(), request.interactionId(), request.customerUtterance());
        return ResponseEntity.ok(dto);
    }

    @PostMapping("/feedback")
    @PreAuthorize("hasAuthority('callcenter.copilot:write') or hasRole('ADMIN')")
    public ResponseEntity<Void> registerFeedback(@RequestBody CopilotFeedbackRequest request) {
        boolean ok = copilotService.registerFeedback(request.logId(), request.feedback());
        if (ok) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }

    @GetMapping("/history/{agentId}")
    @PreAuthorize("hasAuthority('callcenter.copilot:read') or hasRole('ADMIN')")
    public ResponseEntity<List<AgentCopilotService.CopilotSuggestionDto>> getHistory(
            @PathVariable Long agentId) {
        return ResponseEntity.ok(copilotService.getHistoryForAgent(agentId));
    }

    public record LiveTurnRequest(Long agentId, String interactionId, String customerUtterance) {}

    public record CopilotFeedbackRequest(Long logId, String feedback) {}
}
