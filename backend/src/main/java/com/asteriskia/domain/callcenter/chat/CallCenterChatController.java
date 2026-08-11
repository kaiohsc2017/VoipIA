package com.asteriskia.domain.callcenter.chat;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CallCenterChatController — fila e conversas do agente autenticado (Fase 7a). RBAC via
 * {@code PERM_READ_callcenter.chat}/{@code PERM_WRITE_callcenter.chat} (ver SecurityConfig) —
 * {@code /test/**} (simulador de cliente) tem controller e matcher próprios, ROLE_ADMIN puro.
 */
@RestController
@RequestMapping("/api/v1/callcenter/chat")
@RequiredArgsConstructor
public class CallCenterChatController {

    private final CcChatService chatService;

    public record MessageRequest(@NotBlank String text) {}

    public record CloseRequest(Long dispositionId) {}

    @GetMapping("/queue/{queueId}")
    public ResponseEntity<List<CcChatSession>> waiting(@PathVariable Long queueId) {
        return ResponseEntity.ok(chatService.listWaiting(queueId));
    }

    @GetMapping("/mine")
    public ResponseEntity<List<CcChatSession>> mine() {
        return ResponseEntity.ok(chatService.listMine());
    }

    @PostMapping("/{id}/claim")
    public ResponseEntity<CcChatSession> claim(@PathVariable Long id) {
        return ResponseEntity.ok(chatService.claim(id));
    }

    @GetMapping("/{id}/messages")
    public ResponseEntity<List<CcChatMessage>> messages(@PathVariable Long id) {
        return ResponseEntity.ok(chatService.listMessages(id));
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<CcChatMessage> postMessage(@PathVariable Long id, @jakarta.validation.Valid @RequestBody MessageRequest request) {
        return ResponseEntity.ok(chatService.postMessage(id, "agent", null, request.text()));
    }

    @PostMapping("/{id}/close")
    public ResponseEntity<CcChatSession> close(@PathVariable Long id, @RequestBody CloseRequest request) {
        return ResponseEntity.ok(chatService.close(id, request.dispositionId()));
    }
}
