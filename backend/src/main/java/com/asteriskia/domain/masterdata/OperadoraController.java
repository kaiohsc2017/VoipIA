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
 * OperadoraController — CRUD de Operadoras de telecom (referenciadas por Números 0800 e Linhas no
 * bloco Cadastros), extraído de MasterDataController (fase 10 da refatoração).
 */
@RestController
@RequestMapping("/api/v1/operadoras")
@RequiredArgsConstructor
@Transactional
public class OperadoraController {

    private final OperadoraRepository operadoraRepo;
    private final AuditService auditService;

    @GetMapping
    public ResponseEntity<List<Operadora>> listOperadoras(
            @RequestParam(required = false) Boolean active) {
        List<Operadora> result =
                active != null ? operadoraRepo.findByIsActive(active) : operadoraRepo.findAll();
        return ResponseEntity.ok(result);
    }

    @PostMapping
    public ResponseEntity<Operadora> createOperadora(
            @Valid @RequestBody Operadora operadora, HttpServletRequest req) {
        operadora.setId(
                null); // client-supplied id faria merge sobre um registro existente em vez de criar
        // um novo
        Operadora saved = operadoraRepo.save(operadora);
        auditService.log(
                req,
                "MASTERDATA_CREATE",
                "Operadora criada: '" + saved.getNome() + "' (id=" + saved.getId() + ")",
                true);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Operadora> updateOperadora(
            @PathVariable Integer id,
            @Valid @RequestBody Operadora operadora,
            HttpServletRequest req) {
        operadora.setId(id);
        Operadora saved = operadoraRepo.save(operadora);
        auditService.log(
                req,
                "MASTERDATA_UPDATE",
                "Operadora atualizada: '" + saved.getNome() + "' (id=" + id + ")",
                true);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteOperadora(@PathVariable Integer id, HttpServletRequest req) {
        auditService.log(req, "MASTERDATA_DELETE", "Operadora removida (id=" + id + ")", true);
        operadoraRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
