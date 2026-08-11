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

/**
 * CallCenterPauseReasonController — CRUD dos motivos de pausa do Desktop do Agente (Fase 12.6).
 * Até esta entrega só existia o seed da V47 (ALMOCO/BANHEIRO/FEEDBACK/TREINAMENTO), sem UI de
 * cadastro — mesmo padrão simples de {@code CallCenterSkillController}.
 */
@RestController
@RequestMapping("/api/v1/callcenter/pause-reasons")
@RequiredArgsConstructor
public class CallCenterPauseReasonController {

    private final CcPauseReasonRepository repository;

    public record PauseReasonRequest(
            @NotBlank String code, @NotBlank String label, Boolean productive, Boolean active) {}

    @GetMapping
    public ResponseEntity<List<CcPauseReason>> getAll() {
        return ResponseEntity.ok(repository.findAll());
    }

    @PostMapping
    public ResponseEntity<CcPauseReason> create(@Valid @RequestBody PauseReasonRequest request) {
        var reason =
                CcPauseReason.builder()
                        .code(request.code())
                        .label(request.label())
                        .productive(Boolean.TRUE.equals(request.productive()))
                        .active(request.active() == null || request.active())
                        .build();
        return ResponseEntity.status(HttpStatus.CREATED).body(repository.save(reason));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CcPauseReason> update(
            @PathVariable Long id, @Valid @RequestBody PauseReasonRequest request) {
        var reason =
                repository
                        .findById(id)
                        .orElseThrow(() -> new IllegalArgumentException("Motivo de pausa não encontrado: " + id));
        reason.setCode(request.code());
        reason.setLabel(request.label());
        reason.setProductive(Boolean.TRUE.equals(request.productive()));
        if (request.active() != null) {
            reason.setActive(request.active());
        }
        return ResponseEntity.ok(repository.save(reason));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
