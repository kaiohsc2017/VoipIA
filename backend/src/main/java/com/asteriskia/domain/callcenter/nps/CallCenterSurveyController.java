package com.asteriskia.domain.callcenter.nps;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CallCenterSurveyController — CRUD de pesquisas de satisfação (Fase 21). RBAC
 * {@code callcenter.config}, mesmo resource já usado por motivos de pausa/tabulações/ranges de
 * ramal — pesquisa de satisfação é configuração do Call Center, não dado operacional do dia a
 * dia.
 *
 * GET  /api/v1/callcenter/surveys        — lista
 * GET  /api/v1/callcenter/surveys/{id}   — detalhe
 * POST /api/v1/callcenter/surveys        — cria
 * PUT  /api/v1/callcenter/surveys/{id}   — atualiza (bloqueado se já tem resposta)
 * PUT  /api/v1/callcenter/surveys/{id}/active — ativa/desativa
 */
@RestController
@RequestMapping("/api/v1/callcenter/surveys")
@RequiredArgsConstructor
public class CallCenterSurveyController {

    private final CcSurveyService service;

    @GetMapping
    public ResponseEntity<List<SurveyDto>> findAll() {
        return ResponseEntity.ok(service.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<SurveyDto> findById(@PathVariable Long id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @PostMapping
    public ResponseEntity<SurveyDto> create(@Valid @RequestBody SurveyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<SurveyDto> update(@PathVariable Long id, @Valid @RequestBody SurveyRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    public record ActiveRequest(boolean active) {}

    @PutMapping("/{id}/active")
    public ResponseEntity<SurveyDto> setActive(@PathVariable Long id, @RequestBody ActiveRequest request) {
        return ResponseEntity.ok(service.setActive(id, request.active()));
    }
}
