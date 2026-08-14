package com.asteriskia.domain.callcenter.chat;

import jakarta.validation.constraints.NotBlank;
import java.io.File;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

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
    private final ChatAttachmentService attachmentService;

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

    /** Fase 7d — anexo enviado pelo agente. Reusa a mesma regra de posse de
     * {@link CcChatService#validateSender} (dentro de {@code ChatAttachmentService.upload}). */
    @PostMapping("/{id}/attachments")
    public ResponseEntity<CcChatAttachment> uploadAttachment(
            @PathVariable Long id, @RequestParam("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(attachmentService.upload(id, "agent", file));
    }

    @GetMapping("/{id}/attachments")
    public ResponseEntity<List<CcChatAttachment>> attachments(@PathVariable Long id) {
        chatService.assertStaffCanAccess(id);
        return ResponseEntity.ok(attachmentService.list(id));
    }

    @GetMapping("/attachments/{attachmentId}/download")
    public ResponseEntity<org.springframework.core.io.Resource> downloadAttachment(@PathVariable Long attachmentId) {
        var attachment = attachmentService.findByIdOrThrow(attachmentId);
        chatService.assertStaffCanAccess(attachment.getSessionId());
        File file = attachmentService.resolveFile(attachment);
        if (!file.isFile()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Arquivo do anexo não encontrado em disco.");
        }
        return ResponseEntity.ok()
                .contentType(safeMediaType(attachment.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(attachment.getOriginalFileName()))
                .body(new FileSystemResource(file));
    }

    /** Content-Type gravado pode vir do próprio upload (nunca sanitizado) quando a extensão não
     * tem mimetype configurado no catálogo — nunca deixamos um valor malformado quebrar a
     * resposta com 500; cai para octet-stream (que o navegador trata como download genérico,
     * nunca executa/renderiza inline). */
    /** Fase 7d — nome original do arquivo é controlado pelo cliente (agente ou cliente do
     * widget); nunca interpolado cru num header — {@link org.springframework.http.ContentDisposition}
     * já aplica a codificação RFC 6266/5987 correta (escapa aspas/controle, cobre acentuação). */
    private String contentDisposition(String originalFileName) {
        return org.springframework.http.ContentDisposition.attachment().filename(originalFileName, java.nio.charset.StandardCharsets.UTF_8)
                .build().toString();
    }

    private MediaType safeMediaType(String contentType) {
        if (contentType == null) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (org.springframework.http.InvalidMediaTypeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
