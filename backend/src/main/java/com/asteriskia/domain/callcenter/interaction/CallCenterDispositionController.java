package com.asteriskia.domain.callcenter.interaction;

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
 * CallCenterDispositionController — CRUD das tabulações usadas ao encerrar o ACW (Fase 12.6).
 * Tabulação é obrigatória para o agente sair do ACW (Fase 4) — sem esta tela, a operação real
 * ficava presa ao seed inicial, sem forma de cadastrar um motivo de encerramento novo.
 */
@RestController
@RequestMapping("/api/v1/callcenter/dispositions")
@RequiredArgsConstructor
public class CallCenterDispositionController {

    private final CcDispositionRepository repository;

    public record DispositionRequest(@NotBlank String code, @NotBlank String label, Boolean active) {}

    @GetMapping
    public ResponseEntity<List<CcDisposition>> getAll() {
        return ResponseEntity.ok(repository.findAll());
    }

    @PostMapping
    public ResponseEntity<CcDisposition> create(@Valid @RequestBody DispositionRequest request) {
        var disposition =
                CcDisposition.builder()
                        .code(request.code())
                        .label(request.label())
                        .active(request.active() == null || request.active())
                        .build();
        return ResponseEntity.status(HttpStatus.CREATED).body(repository.save(disposition));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CcDisposition> update(
            @PathVariable Long id, @Valid @RequestBody DispositionRequest request) {
        var disposition =
                repository
                        .findById(id)
                        .orElseThrow(() -> new IllegalArgumentException("Tabulação não encontrada: " + id));
        disposition.setCode(request.code());
        disposition.setLabel(request.label());
        if (request.active() != null) {
            disposition.setActive(request.active());
        }
        return ResponseEntity.ok(repository.save(disposition));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
