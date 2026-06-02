package com.asteriskia.domain.call;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * CallRecordController — Endpoints REST para registros de chamada (Módulo 1).
 *
 * POST /api/v1/calls/register — consumido pelo agente Python (JiraCallFlow)
 * GET  /api/v1/calls           — lista paginada de chamadas
 * GET  /api/v1/calls/{id}      — detalhe de uma chamada
 */
@RestController
@RequestMapping("/api/v1/calls")
@RequiredArgsConstructor
@Tag(name = "Call Records", description = "Registro de chamadas da URA e integração com Jira (Módulo 1)")
public class CallRecordController {

    private final CallRecordService service;

    /**
     * Registra chamada da URA e cria issue no Jira.
     * Payload esperado pelo agente Python:
     * {
     *   "callUuid": "...",
     *   "fields": { "customfield_nome_cliente": "...", "description": "..." }
     * }
     */
    @PostMapping("/register")
    @Operation(summary = "Registra chamada da URA e abre issue no Jira")
    public ResponseEntity<RegisterCallResponse> registerCall(
            @Valid @RequestBody RegisterCallRequest request) {
        CallRecord record = service.registerCall(request.callUuid(), request.fields());
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(new RegisterCallResponse(record.getId(), record.getJiraIssueKey()));
    }

    @GetMapping
    @Operation(summary = "Lista chamadas (paginado)")
    public ResponseEntity<Page<CallRecord>> listCalls(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String callerNumber) {
        PageRequest pageable = PageRequest.of(page, size);
        Page<CallRecord> result = callerNumber != null
                ? service.findByCallerNumber(callerNumber, pageable)
                : service.findAll(pageable);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Detalhe de uma chamada")
    public ResponseEntity<CallRecord> getCall(@PathVariable Long id) {
        return ResponseEntity.ok(service.findById(id));
    }

    // ---------------------------------------------------------------------------
    // DTOs
    // ---------------------------------------------------------------------------

    /** Request do agente Python para registrar a chamada. */
    public record RegisterCallRequest(
            @NotBlank String callUuid,
            Map<String, String> fields
    ) {}

    /** Resposta com o ID interno e a chave do issue Jira. */
    public record RegisterCallResponse(
            Long id,
            String jiraIssueKey
    ) {}
}
