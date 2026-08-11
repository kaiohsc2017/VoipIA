package com.asteriskia.domain.ura;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * UraController — Endpoints REST para CRUD de URAs (Módulo 1).
 *
 * GET    /api/v1/uras       — lista todas as URAs
 * GET    /api/v1/uras/{id}  — detalhe de uma URA
 * POST   /api/v1/uras       — cria URA (semeia mensagens padrão automaticamente)
 * PUT    /api/v1/uras/{id}  — atualiza URA
 * DELETE /api/v1/uras/{id}  — remove URA (bloqueado para a URA padrão id=1)
 */
@RestController
@RequestMapping("/api/v1/uras")
@RequiredArgsConstructor
public class UraController {

    private final UraService service;

    @GetMapping
    public ResponseEntity<List<Ura>> getAll() {
        return ResponseEntity.ok(service.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Ura> getById(@PathVariable Integer id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @PostMapping
    public ResponseEntity<Ura> create(@Valid @RequestBody Ura ura) {
        ura.setId(null);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.save(ura));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Ura> update(@PathVariable Integer id, @Valid @RequestBody Ura ura) {
        ura.setId(id);
        return ResponseEntity.ok(service.save(ura));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Integer id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
