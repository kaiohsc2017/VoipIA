package com.asteriskia.domain.masterdata;

import com.asteriskia.domain.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
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
 * BusinessUnitController — CRUD de Unidades de Negócio (Módulo 2), extraído de MasterDataController
 * (fase 10 da refatoração). Registra criações, atualizações e remoções no AuditLog.
 */
@RestController
@RequestMapping("/api/v1/business-units")
@RequiredArgsConstructor
@Transactional
public class BusinessUnitController {

    private final BusinessUnitRepository buRepo;
    private final AuditService auditService;

    @GetMapping
    public ResponseEntity<List<BusinessUnit>> listBUs(
            @RequestParam(required = false) Boolean active) {
        List<BusinessUnit> result =
                active != null ? buRepo.findByIsActive(active) : buRepo.findAll();
        if (BusinessUnitContext.isRestricted()) {
            var allowed = BusinessUnitContext.currentBusinessUnitIds();
            result = result.stream().filter(bu -> allowed.contains(bu.getId())).toList();
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping
    public ResponseEntity<BusinessUnit> createBU(
            @Valid @RequestBody BusinessUnit bu, HttpServletRequest req) {
        BusinessUnit saved = buRepo.save(bu);
        auditService.log(
                req,
                "MASTERDATA_CREATE",
                "BusinessUnit criada: '" + saved.getName() + "' (id=" + saved.getId() + ")",
                true);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<BusinessUnit> updateBU(
            @PathVariable Integer id, @Valid @RequestBody BusinessUnit bu, HttpServletRequest req) {
        bu.setId(id);
        BusinessUnit saved = buRepo.save(bu);
        auditService.log(
                req,
                "MASTERDATA_UPDATE",
                "BusinessUnit atualizada: '" + saved.getName() + "' (id=" + id + ")",
                true);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBU(@PathVariable Integer id, HttpServletRequest req) {
        auditService.log(req, "MASTERDATA_DELETE", "BusinessUnit removida (id=" + id + ")", true);
        buRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
