package com.asteriskia.domain.datacenter;

import com.asteriskia.domain.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * PhoneNumberController — CRUD do DATACENTER (Módulo 2).
 *
 * A criação/edição delega a lógica de resolução de Cliente e a sincronização
 * automática com o Módulo Conectividade para PhoneNumberSyncService.
 */
@RestController
@RequestMapping("/api/v1/phone-numbers")
@RequiredArgsConstructor
public class PhoneNumberController {

    private final PhoneNumberRepository phoneNumberRepo;
    private final PhoneNumberSyncService syncService;
    private final AuditService auditService;

    @GetMapping
    public ResponseEntity<List<PhoneNumber>> list(
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Integer clientId) {
        List<PhoneNumber> result;
        if (clientId != null) {
            result = phoneNumberRepo.findByClientIdOrderByCreatedAtDesc(clientId);
            if (active != null) {
                result = result.stream().filter(p -> active.equals(p.getIsActive())).toList();
            }
        } else {
            result = active != null ? phoneNumberRepo.findByIsActive(active) : phoneNumberRepo.findAll();
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<PhoneNumber> getOne(@PathVariable Long id) {
        return phoneNumberRepo.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<PhoneNumberSaveResult> create(@Valid @RequestBody PhoneNumberRequest req,
                                                         HttpServletRequest httpReq) {
        PhoneNumberSaveResult result = syncService.createOrUpdate(null, req);
        auditService.log(httpReq, "DATACENTER_CREATE", auditMessage("criado", result), true);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PutMapping("/{id}")
    public ResponseEntity<PhoneNumberSaveResult> update(@PathVariable Long id,
                                                         @Valid @RequestBody PhoneNumberRequest req,
                                                         HttpServletRequest httpReq) {
        PhoneNumberSaveResult result = syncService.createOrUpdate(id, req);
        auditService.log(httpReq, "DATACENTER_UPDATE", auditMessage("atualizado", result), true);
        return ResponseEntity.ok(result);
    }

    private String auditMessage(String acao, PhoneNumberSaveResult result) {
        String base = "Número " + acao + " no DATACENTER: '" + result.phoneNumber().getPhoneNumber() +
                "' (id=" + result.phoneNumber().getId() + ")";
        if (result.clientCreated()) {
            base += " — cliente novo criado automaticamente: '" + result.phoneNumber().getClient().getName() +
                    "' (id=" + result.phoneNumber().getClient().getId() + ")";
        }
        return base;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, HttpServletRequest httpReq) {
        PhoneNumber pn = phoneNumberRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Número não encontrado: " + id));
        syncService.beforeDelete(pn);
        phoneNumberRepo.deleteById(id);
        auditService.log(httpReq, "DATACENTER_DELETE",
                "Número removido do DATACENTER: '" + pn.getPhoneNumber() + "' (id=" + id + ")", true);
        return ResponseEntity.noContent().build();
    }
}
