package com.asteriskia.domain.callcenter.chat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CallCenterChatTestController — simulador de cliente para validar o pipeline de chat ponta a
 * ponta (fila → claim → mensagens → encerramento) antes do widget público real (Fase 7b, que vai
 * desenhar com calma um esquema de autenticação anônima para o cliente final). NUNCA exponha
 * este controller a clientes reais — é ferramenta de dev/QA, por isso protegido por
 * {@code ROLE_ADMIN} puro (sem resource_key granular), sem relação com {@code callcenter.chat}
 * usado pelo restante do canal.
 */
@RestController
@RequestMapping("/api/v1/callcenter/chat/test")
@RequiredArgsConstructor
public class CallCenterChatTestController {

    private final CcChatService chatService;

    public record StartSessionRequest(
            @NotBlank String channelCode, @NotNull Long queueId, @NotBlank String customerRef, String customerName) {}

    public record CustomerMessageRequest(@NotBlank String text) {}

    @PostMapping("/sessions")
    public ResponseEntity<CcChatSession> startSession(@Valid @RequestBody StartSessionRequest request) {
        return ResponseEntity.ok(chatService.startSession(
                request.channelCode(), request.queueId(), request.customerRef(), request.customerName()));
    }

    @PostMapping("/sessions/{id}/messages")
    public ResponseEntity<CcChatMessage> postCustomerMessage(
            @PathVariable Long id, @Valid @RequestBody CustomerMessageRequest request) {
        return ResponseEntity.ok(chatService.postMessage(id, "customer", null, request.text()));
    }
}
