package com.asteriskia.domain.callcenter.ia;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CallCenterIaAgentController — CRUD das configurações de persona/prompt/modelo do nó
 * "agente_ia" (Fase A do plano-mãe do Call Center). RBAC via
 * {@code PERM_READ/WRITE_callcenter.ia_agentes} (SecurityConfig). {@code @Transactional} de
 * classe: {@code fallbackQueue} é LAZY e é acessado na serialização de {@link IaAgentResponse}
 * (mesmo cuidado de {@code UserController}, {@code spring.jpa.open-in-view=false} neste projeto).
 */
@RestController
@RequestMapping("/api/v1/callcenter/ia-agents")
@RequiredArgsConstructor
@Transactional
public class CallCenterIaAgentController {

    private final CallCenterIaAgentService service;

    @GetMapping
    public ResponseEntity<List<IaAgentResponse>> list(
            @RequestParam(defaultValue = "false") boolean activeOnly) {
        return ResponseEntity.ok(service.list(activeOnly).stream().map(IaAgentResponse::from).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<IaAgentResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(IaAgentResponse.from(service.getById(id)));
    }

    @PostMapping
    public ResponseEntity<IaAgentResponse> create(@Valid @RequestBody IaAgentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(IaAgentResponse.from(service.create(request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<IaAgentResponse> update(
            @PathVariable Long id, @Valid @RequestBody IaAgentRequest request) {
        return ResponseEntity.ok(IaAgentResponse.from(service.update(id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
