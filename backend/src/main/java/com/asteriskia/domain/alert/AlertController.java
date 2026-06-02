package com.asteriskia.domain.alert;

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

import java.util.List;

/**
 * AlertController — Endpoints REST para alertas Zabbix e contatos de plantão (Módulo 3).
 *
 * GET  /api/v1/alert-calls/by-uuid/{uuid}  → consumido pelo agente Python (ZabbixAlertFlow)
 * PATCH /api/v1/alert-calls/by-uuid/{uuid} → agente Python atualiza status após chamada
 * GET  /api/v1/alert-calls                 → histórico de alertas (frontend)
 * GET/POST/DELETE /api/v1/alert-contacts   → CRUD de contatos de plantão
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Alerts", description = "Alertas de infraestrutura Zabbix e contatos de plantão (Módulo 3)")
public class AlertController {

    private final AlertService service;

    // -----------------------------------------------------------------------
    // Alert Calls
    // -----------------------------------------------------------------------

    /** Consumido pelo agente Python: busca dados do incidente para leitura via TTS. */
    @GetMapping("/alert-calls/by-uuid/{uuid}")
    @Operation(summary = "Busca alert call pelo UUID do Asterisk")
    public ResponseEntity<AlertCall> getByUuid(@PathVariable String uuid) {
        return service.findByUuid(uuid)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Consumido pelo agente Python: atualiza status após o fluxo de voz. */
    @PatchMapping("/alert-calls/by-uuid/{uuid}")
    @Operation(summary = "Atualiza status da chamada de alerta")
    public ResponseEntity<Void> updateStatus(
            @PathVariable String uuid,
            @Valid @RequestBody UpdateStatusRequest request) {
        service.updateCallStatus(uuid, request.callStatus());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/alert-calls")
    @Operation(summary = "Lista histórico de alertas (paginado)")
    public ResponseEntity<Page<AlertCall>> listAlerts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.findAll(PageRequest.of(page, size)));
    }

    // -----------------------------------------------------------------------
    // Alert Contacts (contatos de plantão)
    // -----------------------------------------------------------------------

    @GetMapping("/alert-contacts")
    @Operation(summary = "Lista contatos de plantão ativos")
    public ResponseEntity<List<AlertContact>> listContacts() {
        return ResponseEntity.ok(service.findActiveContacts());
    }

    @PostMapping("/alert-contacts")
    @Operation(summary = "Cadastra contato de plantão")
    public ResponseEntity<AlertContact> createContact(@Valid @RequestBody AlertContact contact) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.saveContact(contact));
    }

    @PutMapping("/alert-contacts/{id}")
    @Operation(summary = "Atualiza contato de plantão")
    public ResponseEntity<AlertContact> updateContact(
            @PathVariable Integer id, @Valid @RequestBody AlertContact contact) {
        contact.setId(id);
        return ResponseEntity.ok(service.saveContact(contact));
    }

    @DeleteMapping("/alert-contacts/{id}")
    @Operation(summary = "Remove contato de plantão")
    public ResponseEntity<Void> deleteContact(@PathVariable Integer id) {
        service.deleteContact(id);
        return ResponseEntity.noContent().build();
    }

    // -----------------------------------------------------------------------
    // DTOs
    // -----------------------------------------------------------------------

    public record UpdateStatusRequest(@NotBlank String callStatus) {}
}
