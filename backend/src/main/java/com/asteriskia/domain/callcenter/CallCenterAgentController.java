package com.asteriskia.domain.callcenter;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
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
 * CallCenterAgentController — CRUD de agentes/ramais do Call Center (Fase 2).
 *
 * GET    /api/v1/callcenter/agentes                    — lista (escopo por BU)
 * GET    /api/v1/callcenter/agentes/{id}                — detalhe
 * POST   /api/v1/callcenter/agentes                     — cria agente + provisiona ramal ARA
 * PUT    /api/v1/callcenter/agentes/{id}                — atualiza dados do agente
 * DELETE /api/v1/callcenter/agentes/{id}                — remove agente + desprovisiona ramal ARA
 * GET    /api/v1/callcenter/agentes/{id}/ramal-secret    — senha do ramal (callcenter.ramais)
 */
@RestController
@RequestMapping("/api/v1/callcenter/agentes")
@RequiredArgsConstructor
public class CallCenterAgentController {

    private final CallCenterAgentService service;

    @GetMapping
    public ResponseEntity<List<CcAgent>> getAll() {
        return ResponseEntity.ok(service.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<CcAgent> getById(@PathVariable Long id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @PostMapping
    public ResponseEntity<CcAgent> create(@Valid @RequestBody AgentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CcAgent> update(@PathVariable Long id, @Valid @RequestBody AgentRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/ramal-secret")
    public ResponseEntity<Map<String, String>> ramalSecret(@PathVariable Long id) {
        return ResponseEntity.ok(Map.of("secret", service.extensionSecret(id)));
    }
}
