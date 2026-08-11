package com.asteriskia.domain.callcenter.chat;

import com.asteriskia.config.ResourceNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CallCenterCannedResponseController — CRUD do catálogo compartilhado de respostas rápidas do
 * chat (Fase 7a). Sem posse individual, mesmo padrão de {@code CcDisposition} — RBAC via
 * {@code PERM_READ/WRITE_callcenter.chat}.
 */
@RestController
@RequestMapping("/api/v1/callcenter/chat/canned-responses")
@RequiredArgsConstructor
public class CallCenterCannedResponseController {

    private final CcCannedResponseRepository repository;

    public record CannedResponseRequest(@NotBlank String title, @NotBlank String body, String category) {}

    @GetMapping
    public ResponseEntity<List<CcCannedResponse>> list() {
        return ResponseEntity.ok(repository.findByActiveTrueOrderByTitleAsc());
    }

    @PostMapping
    public ResponseEntity<CcCannedResponse> create(@Valid @RequestBody CannedResponseRequest request) {
        CcCannedResponse saved = repository.save(CcCannedResponse.builder()
                .title(request.title())
                .body(request.body())
                .category(request.category())
                .active(true)
                .build());
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<CcCannedResponse> update(@PathVariable Long id, @Valid @RequestBody CannedResponseRequest request) {
        CcCannedResponse existing = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Resposta rápida não encontrada: " + id));
        existing.setTitle(request.title());
        existing.setBody(request.body());
        existing.setCategory(request.category());
        return ResponseEntity.ok(repository.save(existing));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        CcCannedResponse existing = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Resposta rápida não encontrada: " + id));
        existing.setActive(false);
        repository.save(existing);
        return ResponseEntity.noContent().build();
    }
}
