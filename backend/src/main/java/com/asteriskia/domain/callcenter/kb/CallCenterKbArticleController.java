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
 * CallCenterKbArticleController — CRUD de artigos da base de conhecimento própria do Call Center
 * (Fase 25, §25.1). {@code version} incrementa a cada edição (create/update/desativar) — a
 * reindexação de verdade (chunking + embedding) é assíncrona, feita por
 * {@code CallCenterKbIndexingScheduler} quando percebe {@code version <> indexedVersion}, nunca
 * no próprio request de escrita (evitaria bloquear a UI numa chamada HTTP ao servidor de
 * embeddings). RBAC via {@code PERM_READ/WRITE_callcenter.kb}.
 */
@RestController
@RequestMapping("/api/v1/callcenter/kb/articles")
@RequiredArgsConstructor
public class CallCenterKbArticleController {

    private final CcKbArticleRepository repository;

    public record ArticleRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 50_000) String body,
            @Size(max = 500) String tags) {}

    @GetMapping
    public ResponseEntity<List<CcKbArticle>> list() {
        return ResponseEntity.ok(repository.findAllByOrderByTitleAsc());
    }

    @PostMapping
    public ResponseEntity<CcKbArticle> create(@Valid @RequestBody ArticleRequest request) {
        var saved =
                repository.save(
                        CcKbArticle.builder()
                                .title(request.title())
                                .body(request.body())
                                .tags(request.tags())
                                .active(true)
                                .version(1)
                                .indexedVersion(0)
                                .build());
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<CcKbArticle> update(@PathVariable Long id, @Valid @RequestBody ArticleRequest request) {
        var existing = findOrThrow(id);
        existing.setTitle(request.title());
        existing.setBody(request.body());
        existing.setTags(request.tags());
        existing.setVersion(existing.getVersion() + 1);
        return ResponseEntity.ok(repository.save(existing));
    }

    /** Desativar também conta como edição (version++) — o scheduler reindexa (na prática, apaga
     * os chunks) assim que perceber a divergência, sem precisar de um caminho separado de
     * remoção síncrona aqui. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        var existing = findOrThrow(id);
        existing.setActive(false);
        existing.setVersion(existing.getVersion() + 1);
        repository.save(existing);
        return ResponseEntity.noContent().build();
    }

    private CcKbArticle findOrThrow(Long id) {
        return repository
                .findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Artigo não encontrado: " + id));
    }
}
