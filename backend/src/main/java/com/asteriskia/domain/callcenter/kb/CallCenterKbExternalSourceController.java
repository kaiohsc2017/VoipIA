package com.asteriskia.domain.callcenter.kb;

import com.asteriskia.config.ResourceNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
 * CallCenterKbExternalSourceController — cadastro de fontes externas por URL (Fase 25, §25.2,
 * D22). A busca/indexação em si é assíncrona ({@code CallCenterKbIndexingScheduler}, 1x/dia) —
 * este controller só gerencia o cadastro. RBAC via {@code PERM_READ/WRITE_callcenter.kb}.
 */
@RestController
@RequestMapping("/api/v1/callcenter/kb/sources")
@RequiredArgsConstructor
public class CallCenterKbExternalSourceController {

    private final CcKbExternalSourceRepository repository;

    public record SourceRequest(@NotBlank @Size(max = 500) String url) {}

    @GetMapping
    public ResponseEntity<List<CcKbExternalSource>> list() {
        return ResponseEntity.ok(repository.findAllByOrderByUrlAsc());
    }

    @PostMapping
    public ResponseEntity<CcKbExternalSource> create(@Valid @RequestBody SourceRequest request) {
        var saved =
                repository.save(CcKbExternalSource.builder().url(request.url()).active(true).build());
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<CcKbExternalSource> update(
            @PathVariable Long id, @Valid @RequestBody SourceRequest request) {
        var existing = findOrThrow(id);
        existing.setUrl(request.url());
        return ResponseEntity.ok(repository.save(existing));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        var existing = findOrThrow(id);
        existing.setActive(false);
        repository.save(existing);
        return ResponseEntity.noContent().build();
    }

    private CcKbExternalSource findOrThrow(Long id) {
        return repository
                .findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Fonte externa não encontrada: " + id));
    }
}
