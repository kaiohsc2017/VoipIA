package com.asteriskia.domain.callcenter;

import jakarta.validation.Valid;
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
 * CallCenterQueueController — CRUD de filas e membros do Call Center (Fase 2).
 *
 * GET    /api/v1/callcenter/filas                          — lista (escopo por BU)
 * POST   /api/v1/callcenter/filas                           — cria fila + espelha em ARA
 * PUT    /api/v1/callcenter/filas/{id}                      — atualiza fila
 * DELETE /api/v1/callcenter/filas/{id}                      — remove fila
 * GET    /api/v1/callcenter/filas/{id}/membros              — lista agentes da fila
 * POST   /api/v1/callcenter/filas/{id}/membros/{agentId}    — inclui agente na fila
 * DELETE /api/v1/callcenter/filas/{id}/membros/{agentId}    — remove agente da fila
 */
@RestController
@RequestMapping("/api/v1/callcenter/filas")
@RequiredArgsConstructor
public class CallCenterQueueController {

    private final CallCenterQueueService service;

    @GetMapping
    public ResponseEntity<List<CcQueue>> getAll() {
        return ResponseEntity.ok(service.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<CcQueue> getById(@PathVariable Long id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @PostMapping
    public ResponseEntity<CcQueue> create(@Valid @RequestBody QueueRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CcQueue> update(@PathVariable Long id, @Valid @RequestBody QueueRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/membros")
    public ResponseEntity<List<CcQueueMember>> members(@PathVariable Long id) {
        return ResponseEntity.ok(service.members(id));
    }

    @PostMapping("/{id}/membros/{agentId}")
    public ResponseEntity<CcQueueMember> addMember(@PathVariable Long id, @PathVariable Long agentId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addMember(id, agentId));
    }

    @DeleteMapping("/{id}/membros/{agentId}")
    public ResponseEntity<Void> removeMember(@PathVariable Long id, @PathVariable Long agentId) {
        service.removeMember(id, agentId);
        return ResponseEntity.noContent().build();
    }
}
