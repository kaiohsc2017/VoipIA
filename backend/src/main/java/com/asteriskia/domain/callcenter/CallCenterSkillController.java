package com.asteriskia.domain.callcenter;

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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** CallCenterSkillController — CRUD simples de skills (Fase 2; roteamento por skill entra na Fase 5). */
@RestController
@RequestMapping("/api/v1/callcenter/skills")
@RequiredArgsConstructor
public class CallCenterSkillController {

    private final CcSkillRepository repository;

    public record SkillRequest(@NotBlank String name, String description) {}

    @GetMapping
    public ResponseEntity<List<CcSkill>> getAll() {
        return ResponseEntity.ok(repository.findAll());
    }

    @PostMapping
    public ResponseEntity<CcSkill> create(@Valid @RequestBody SkillRequest request) {
        var skill = CcSkill.builder().name(request.name()).description(request.description()).build();
        return ResponseEntity.status(HttpStatus.CREATED).body(repository.save(skill));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CcSkill> update(@PathVariable Long id, @Valid @RequestBody SkillRequest request) {
        // Fase 19 (Parte III) — ResponseStatusException(404), não IllegalArgumentException:
        // antes caía no catch-all de RuntimeException e virava 500 genérico para id inexistente.
        var skill =
                repository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Skill não encontrada: " + id));
        skill.setName(request.name());
        skill.setDescription(request.description());
        return ResponseEntity.ok(repository.save(skill));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
