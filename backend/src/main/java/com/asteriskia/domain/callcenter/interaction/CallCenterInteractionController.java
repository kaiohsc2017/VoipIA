package com.asteriskia.domain.callcenter.interaction;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CallCenterInteractionController — interação em curso do agente autenticado e tabulação
 * (Fase 4). RBAC via {@code PERM_READ_callcenter.desktop}/{@code PERM_WRITE_callcenter.desktop}.
 *
 * <p>GET /api/v1/callcenter/interactions/current — interação em atendimento pelo agente
 * autenticado (para o screen pop) GET /api/v1/callcenter/interactions/dispositions — catálogo de
 * tabulações ativas POST /api/v1/callcenter/interactions/disposition — aplica a tabulação e
 * encerra o ACW
 */
@RestController
@RequestMapping("/api/v1/callcenter/interactions")
@RequiredArgsConstructor
public class CallCenterInteractionController {

    private final CallCenterInteractionService service;
    private final CcDispositionRepository dispositionRepository;

    @GetMapping("/current")
    public ResponseEntity<InteractionView> current() {
        return ResponseEntity.ok(service.currentInteraction());
    }

    @GetMapping("/dispositions")
    public ResponseEntity<List<CcDisposition>> dispositions() {
        return ResponseEntity.ok(dispositionRepository.findByActiveTrue());
    }

    @PostMapping("/disposition")
    public ResponseEntity<InteractionView> applyDisposition(
            @Valid @RequestBody DispositionRequest request) {
        return ResponseEntity.ok(service.applyDisposition(request));
    }
}
