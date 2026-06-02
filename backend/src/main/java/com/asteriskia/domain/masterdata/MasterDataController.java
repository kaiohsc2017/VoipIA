package com.asteriskia.domain.masterdata;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Master Data", description = "CRUD de BU, Segmentos, Clientes e Operações (Módulo 2)")
public class MasterDataController {

    private final BusinessUnitRepository buRepo;
    private final SegmentRepository segRepo;
    private final ClientRepository clientRepo;
    private final OperationRepository opRepo;

    // -----------------------------------------------------------------------
    // Business Units
    // -----------------------------------------------------------------------

    @GetMapping("/business-units")
    @Operation(summary = "Lista Business Units")
    public ResponseEntity<List<BusinessUnit>> listBUs(@RequestParam(required = false) Boolean active) {
        List<BusinessUnit> result = active != null
                ? buRepo.findByIsActive(active)
                : buRepo.findAll();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/business-units")
    @Operation(summary = "Cria Business Unit")
    public ResponseEntity<BusinessUnit> createBU(@Valid @RequestBody BusinessUnit bu) {
        return ResponseEntity.status(HttpStatus.CREATED).body(buRepo.save(bu));
    }

    @PutMapping("/business-units/{id}")
    @Operation(summary = "Atualiza Business Unit")
    public ResponseEntity<BusinessUnit> updateBU(@PathVariable Integer id, @Valid @RequestBody BusinessUnit bu) {
        bu.setId(id);
        return ResponseEntity.ok(buRepo.save(bu));
    }

    @DeleteMapping("/business-units/{id}")
    @Operation(summary = "Remove Business Unit")
    public ResponseEntity<Void> deleteBU(@PathVariable Integer id) {
        buRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // -----------------------------------------------------------------------
    // Segments
    // -----------------------------------------------------------------------

    @GetMapping("/segments")
    @Operation(summary = "Lista Segmentos")
    public ResponseEntity<List<Segment>> listSegments(@RequestParam(required = false) Boolean active) {
        List<Segment> result = active != null
                ? segRepo.findByIsActive(active)
                : segRepo.findAll();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/segments")
    @Operation(summary = "Cria Segmento")
    public ResponseEntity<Segment> createSegment(@Valid @RequestBody Segment seg) {
        return ResponseEntity.status(HttpStatus.CREATED).body(segRepo.save(seg));
    }

    @PutMapping("/segments/{id}")
    @Operation(summary = "Atualiza Segmento")
    public ResponseEntity<Segment> updateSegment(@PathVariable Integer id, @Valid @RequestBody Segment seg) {
        seg.setId(id);
        return ResponseEntity.ok(segRepo.save(seg));
    }

    @DeleteMapping("/segments/{id}")
    @Operation(summary = "Remove Segmento")
    public ResponseEntity<Void> deleteSegment(@PathVariable Integer id) {
        segRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // -----------------------------------------------------------------------
    // Clients
    // -----------------------------------------------------------------------

    @GetMapping("/clients")
    @Operation(summary = "Lista Clientes")
    public ResponseEntity<List<Client>> listClients(@RequestParam(required = false) Boolean active) {
        List<Client> result = active != null
                ? clientRepo.findByIsActive(active)
                : clientRepo.findAll();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/clients")
    @Operation(summary = "Cria Cliente")
    public ResponseEntity<Client> createClient(@Valid @RequestBody Client client) {
        return ResponseEntity.status(HttpStatus.CREATED).body(clientRepo.save(client));
    }

    @PutMapping("/clients/{id}")
    @Operation(summary = "Atualiza Cliente")
    public ResponseEntity<Client> updateClient(@PathVariable Integer id, @Valid @RequestBody Client client) {
        client.setId(id);
        return ResponseEntity.ok(clientRepo.save(client));
    }

    @DeleteMapping("/clients/{id}")
    @Operation(summary = "Remove Cliente")
    public ResponseEntity<Void> deleteClient(@PathVariable Integer id) {
        clientRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /** Vincula operação a cliente (N:N). */
    @PostMapping("/clients/{clientId}/operations/{operationId}")
    @Operation(summary = "Vincula operação ao cliente")
    @Transactional
    public ResponseEntity<Void> addOperation(@PathVariable Integer clientId, @PathVariable Integer operationId) {
        Client client = clientRepo.findById(clientId)
                .orElseThrow(() -> new RuntimeException("Cliente não encontrado: " + clientId));
        Operation op = opRepo.findById(operationId)
                .orElseThrow(() -> new RuntimeException("Operação não encontrada: " + operationId));
        client.getOperations().add(op);
        clientRepo.save(client);
        return ResponseEntity.noContent().build();
    }

    /** Lista operações disponíveis para um cliente. */
    @GetMapping("/clients/{clientId}/operations")
    @Operation(summary = "Lista operações vinculadas ao cliente")
    public ResponseEntity<List<Operation>> getClientOperations(@PathVariable Integer clientId) {
        Client client = clientRepo.findById(clientId)
                .orElseThrow(() -> new RuntimeException("Cliente não encontrado: " + clientId));
        return ResponseEntity.ok(client.getOperations().stream().toList());
    }

    // -----------------------------------------------------------------------
    // Operations
    // -----------------------------------------------------------------------

    @GetMapping("/operations")
    @Operation(summary = "Lista Operações")
    public ResponseEntity<List<com.asteriskia.domain.masterdata.Operation>> listOps(
            @RequestParam(required = false) Boolean active) {
        List<com.asteriskia.domain.masterdata.Operation> result = active != null
                ? opRepo.findByIsActive(active)
                : opRepo.findAll();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/operations")
    @Operation(summary = "Cria Operação")
    public ResponseEntity<com.asteriskia.domain.masterdata.Operation> createOp(
            @Valid @RequestBody com.asteriskia.domain.masterdata.Operation op) {
        return ResponseEntity.status(HttpStatus.CREATED).body(opRepo.save(op));
    }

    @PutMapping("/operations/{id}")
    @Operation(summary = "Atualiza Operação")
    public ResponseEntity<com.asteriskia.domain.masterdata.Operation> updateOp(
            @PathVariable Integer id,
            @Valid @RequestBody com.asteriskia.domain.masterdata.Operation op) {
        op.setId(id);
        return ResponseEntity.ok(opRepo.save(op));
    }

    @DeleteMapping("/operations/{id}")
    @Operation(summary = "Remove Operação")
    public ResponseEntity<Void> deleteOp(@PathVariable Integer id) {
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
