package com.asteriskia.domain.callcenter.chat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * CallCenterChatAttachmentExtensionController — catálogo de extensões aceitas para anexo no chat
 * (Fase 7d, D6) — cadastrado extensão por extensão pelo operador. Sob o mesmo prefixo
 * {@code /api/v1/callcenter/chat/**}, já protegido por {@code callcenter.chat} — nenhum matcher
 * novo necessário.
 */
@RestController
@RequestMapping("/api/v1/callcenter/chat/attachment-extensions")
@RequiredArgsConstructor
public class CallCenterChatAttachmentExtensionController {

    private final CcChatAttachmentExtensionRepository repository;

    public record ExtensionRequest(@NotBlank String extension, String mimetype) {}

    @GetMapping
    public ResponseEntity<List<CcChatAttachmentExtension>> getAll() {
        return ResponseEntity.ok(repository.findAllByOrderByExtensionAsc());
    }

    @PostMapping
    public ResponseEntity<CcChatAttachmentExtension> create(@Valid @RequestBody ExtensionRequest request) {
        String normalized = request.extension().trim().toLowerCase().replaceFirst("^\\.", "");
        if (normalized.isBlank() || !normalized.matches("[a-z0-9]{1,20}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Extensão inválida — use só letras/números, sem ponto.");
        }
        if (repository.existsByExtensionIgnoreCase(normalized)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Extensão \"" + normalized + "\" já está cadastrada.");
        }
        var saved = repository.save(CcChatAttachmentExtension.builder()
                .extension(normalized)
                .mimetype(request.mimetype())
                .active(true)
                .build());
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
