package com.asteriskia.domain.masterdata;

import com.asteriskia.domain.audit.AuditService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * MasterDataController — CRUD de dados mestres (Módulo 2).
 * Agrupa os 4 recursos: BusinessUnit, Segment, Client, Operation.
 * Registra criações, atualizações e remoções no AuditLog (Fase 13).
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Master Data", description = "CRUD de BU, Segmentos, Clientes e Operações (Módulo 2)")
public class MasterDataController {

    private final BusinessUnitRepository buRepo;
    private final SegmentRepository      segRepo;
    private final ClientRepository       clientRepo;
    private final OperationRepository    opRepo;
    private final AuditService           auditService;

    // -----------------------------------------------------------------------
    // Business Units
    // -----------------------------------------------------------------------

    @GetMapping("/business-units")
    @io.swagger.v3.oas.annotations.Operation(summary = "Lista Business Units")
    public ResponseEntity<List<BusinessUnit>> listBUs(@RequestParam(required = false) Boolean active) {
        List<BusinessUnit> result = active != null
                ? buRepo.findByIsActive(active)
                : buRepo.findAll();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/business-units")
    @io.swagger.v3.oas.annotations.Operation(summary = "Cria Business Unit")
    public ResponseEntity<BusinessUnit> createBU(@Valid @RequestBody BusinessUnit bu,
                                                  HttpServletRequest req) {
        BusinessUnit saved = buRepo.save(bu);
        auditService.log(req, "MASTERDATA_CREATE", "BusinessUnit criada: '" + saved.getName() + "' (id=" + saved.getId() + ")", true);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/business-units/{id}")
    @io.swagger.v3.oas.annotations.Operation(summary = "Atualiza Business Unit")
    public ResponseEntity<BusinessUnit> updateBU(@PathVariable Integer id, @Valid @RequestBody BusinessUnit bu,
                                                  HttpServletRequest req) {
        bu.setId(id);
        BusinessUnit saved = buRepo.save(bu);
        auditService.log(req, "MASTERDATA_UPDATE", "BusinessUnit atualizada: '" + saved.getName() + "' (id=" + id + ")", true);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/business-units/{id}")
    @io.swagger.v3.oas.annotations.Operation(summary = "Remove Business Unit")
    public ResponseEntity<Void> deleteBU(@PathVariable Integer id, HttpServletRequest req) {
        auditService.log(req, "MASTERDATA_DELETE", "BusinessUnit removida (id=" + id + ")", true);
        buRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // -----------------------------------------------------------------------
    // Segments
    // -----------------------------------------------------------------------

    @GetMapping("/segments")
    @io.swagger.v3.oas.annotations.Operation(summary = "Lista Segmentos")
    public ResponseEntity<List<Segment>> listSegments(@RequestParam(required = false) Boolean active) {
        List<Segment> result = active != null
                ? segRepo.findByIsActive(active)
                : segRepo.findAll();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/segments")
    @io.swagger.v3.oas.annotations.Operation(summary = "Cria Segmento")
    public ResponseEntity<Segment> createSegment(@Valid @RequestBody Segment seg, HttpServletRequest req) {
        Segment saved = segRepo.save(seg);
        auditService.log(req, "MASTERDATA_CREATE", "Segmento criado: '" + saved.getName() + "' (id=" + saved.getId() + ")", true);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/segments/{id}")
    @io.swagger.v3.oas.annotations.Operation(summary = "Atualiza Segmento")
    public ResponseEntity<Segment> updateSegment(@PathVariable Integer id, @Valid @RequestBody Segment seg,
                                                  HttpServletRequest req) {
        seg.setId(id);
        Segment saved = segRepo.save(seg);
        auditService.log(req, "MASTERDATA_UPDATE", "Segmento atualizado: '" + saved.getName() + "' (id=" + id + ")", true);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/segments/{id}")
    @io.swagger.v3.oas.annotations.Operation(summary = "Remove Segmento")
    public ResponseEntity<Void> deleteSegment(@PathVariable Integer id, HttpServletRequest req) {
        auditService.log(req, "MASTERDATA_DELETE", "Segmento removido (id=" + id + ")", true);
        segRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // -----------------------------------------------------------------------
    // Clients
    // -----------------------------------------------------------------------

    @GetMapping("/clients")
    @io.swagger.v3.oas.annotations.Operation(summary = "Lista Clientes")
    public ResponseEntity<List<Client>> listClients(@RequestParam(required = false) Boolean active) {
        List<Client> result = active != null
                ? clientRepo.findByIsActive(active)
                : clientRepo.findAll();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/clients")
    @io.swagger.v3.oas.annotations.Operation(summary = "Cria Cliente")
    public ResponseEntity<Client> createClient(@Valid @RequestBody Client client, HttpServletRequest req) {
        Client saved = clientRepo.save(client);
        auditService.log(req, "MASTERDATA_CREATE", "Cliente criado: '" + saved.getName() + "' (id=" + saved.getId() + ")", true);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/clients/{id}")
    @io.swagger.v3.oas.annotations.Operation(summary = "Atualiza Cliente")
    public ResponseEntity<Client> updateClient(@PathVariable Integer id, @Valid @RequestBody Client client,
                                                HttpServletRequest req) {
        client.setId(id);
        Client saved = clientRepo.save(client);
        auditService.log(req, "MASTERDATA_UPDATE", "Cliente atualizado: '" + saved.getName() + "' (id=" + id + ")", true);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/clients/{id}")
    @io.swagger.v3.oas.annotations.Operation(summary = "Remove Cliente")
    public ResponseEntity<Void> deleteClient(@PathVariable Integer id, HttpServletRequest req) {
        auditService.log(req, "MASTERDATA_DELETE", "Cliente removido (id=" + id + ")", true);
        clientRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /** Vincula operação a cliente (N:N). */
    @PostMapping("/clients/{clientId}/operations/{operationId}")
    @io.swagger.v3.oas.annotations.Operation(summary = "Vincula operação ao cliente")
    @Transactional
    public ResponseEntity<Void> addOperation(@PathVariable Integer clientId, @PathVariable Integer operationId,
                                              HttpServletRequest req) {
        Client client = clientRepo.findById(clientId)
                .orElseThrow(() -> new RuntimeException("Cliente não encontrado: " + clientId));
        Operation op = opRepo.findById(operationId)
                .orElseThrow(() -> new RuntimeException("Operação não encontrada: " + operationId));
        client.getOperations().add(op);
        clientRepo.save(client);
        auditService.log(req, "MASTERDATA_UPDATE",
                "Operação '" + op.getName() + "' vinculada ao cliente '" + client.getName() + "'", true);
        return ResponseEntity.noContent().build();
    }

    /** Lista operações disponíveis para um cliente. */
    @GetMapping("/clients/{clientId}/operations")
    @io.swagger.v3.oas.annotations.Operation(summary = "Lista operações vinculadas ao cliente")
    public ResponseEntity<List<Operation>> getClientOperations(@PathVariable Integer clientId) {
        Client client = clientRepo.findById(clientId)
                .orElseThrow(() -> new RuntimeException("Cliente não encontrado: " + clientId));
        return ResponseEntity.ok(client.getOperations().stream().toList());
    }

    // -----------------------------------------------------------------------
    // Operations
    // -----------------------------------------------------------------------

    @GetMapping("/operations")
    @io.swagger.v3.oas.annotations.Operation(summary = "Lista Operações")
    public ResponseEntity<List<Operation>> listOps(@RequestParam(required = false) Boolean active) {
        List<Operation> result = active != null
                ? opRepo.findByIsActive(active)
                : opRepo.findAll();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/operations")
    @io.swagger.v3.oas.annotations.Operation(summary = "Cria Operação")
    public ResponseEntity<Operation> createOp(@Valid @RequestBody Operation op, HttpServletRequest req) {
        Operation saved = opRepo.save(op);
        auditService.log(req, "MASTERDATA_CREATE", "Operação criada: '" + saved.getName() + "' (id=" + saved.getId() + ")", true);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/operations/{id}")
    @io.swagger.v3.oas.annotations.Operation(summary = "Atualiza Operação")
    public ResponseEntity<Operation> updateOp(@PathVariable Integer id, @Valid @RequestBody Operation op,
                                               HttpServletRequest req) {
        op.setId(id);
        Operation saved = opRepo.save(op);
        auditService.log(req, "MASTERDATA_UPDATE", "Operação atualizada: '" + saved.getName() + "' (id=" + id + ")", true);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/operations/{id}")
    @io.swagger.v3.oas.annotations.Operation(summary = "Remove Operação")
    public ResponseEntity<Void> deleteOp(@PathVariable Integer id, HttpServletRequest req) {
        auditService.log(req, "MASTERDATA_DELETE", "Operação removida (id=" + id + ")", true);
        opRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}

// ---------------------------------------------------------------------------
// Repositories — simples, no mesmo arquivo por coesão
// ---------------------------------------------------------------------------

@Repository
interface BusinessUnitRepository extends JpaRepository<BusinessUnit, Integer> {
    List<BusinessUnit> findByIsActive(Boolean isActive);
}

@Repository
interface SegmentRepository extends JpaRepository<Segment, Integer> {
    List<Segment> findByIsActive(Boolean isActive);
}

@Repository
interface ClientRepository extends JpaRepository<Client, Integer> {
    List<Client> findByIsActive(Boolean isActive);
}

@Repository
interface OperationRepository extends JpaRepository<Operation, Integer> {
    List<Operation> findByIsActive(Boolean isActive);
}
