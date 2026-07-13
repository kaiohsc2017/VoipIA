package com.asteriskia.domain.masterdata;

import com.asteriskia.domain.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * OperationController — CRUD de Operações e seus vínculos N:N com Clientes/Unidades de Negócio
 * (Módulo 2), extraído de MasterDataController (fase 10 da refatoração).
 *
 * <p>{@code @Transactional} em nível de classe: Operation carrega businessUnits como coleção EAGER
 * mas é serializada diretamente pelo Jackson — sem uma sessão Hibernate aberta durante a
 * serialização, o acesso lazy (clients) fora de transação lança LazyInitializationException
 * (spring.jpa.open-in-view=false neste projeto).
 */
@RestController
@RequestMapping("/api/v1/operations")
@RequiredArgsConstructor
@Transactional
public class OperationController {

    private final OperationRepository opRepo;
    private final ClientRepository clientRepo;
    private final BusinessUnitRepository buRepo;
    private final AuditService auditService;

    @GetMapping
    public ResponseEntity<List<Operation>> listOps(@RequestParam(required = false) Boolean active) {
        List<Operation> result = active != null ? opRepo.findByIsActive(active) : opRepo.findAll();
        return ResponseEntity.ok(
                MasterDataScopeFilter.filterByBusinessUnitScope(
                        result,
                        o -> o.getBusinessUnits().stream().map(BusinessUnit::getId).toList()));
    }

    @PostMapping
    public ResponseEntity<Operation> createOp(
            @Valid @RequestBody Operation op, HttpServletRequest req) {
        Operation saved = opRepo.save(op);
        auditService.log(
                req,
                "MASTERDATA_CREATE",
                "Operação criada: '" + saved.getName() + "' (id=" + saved.getId() + ")",
                true);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Operation> updateOp(
            @PathVariable Integer id, @Valid @RequestBody Operation op, HttpServletRequest req) {
        op.setId(id);
        Operation saved = opRepo.save(op);
        auditService.log(
                req,
                "MASTERDATA_UPDATE",
                "Operação atualizada: '" + saved.getName() + "' (id=" + id + ")",
                true);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteOp(@PathVariable Integer id, HttpServletRequest req) {
        auditService.log(req, "MASTERDATA_DELETE", "Operação removida (id=" + id + ")", true);
        opRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Sincroniza (substitui por completo) as Unidades de Negócio vinculadas a uma operação. Campo
     * opcional — lista vazia é válida e limpa a associação.
     */
    @PutMapping("/{id}/business-units")
    public ResponseEntity<?> syncOperationBusinessUnits(
            @PathVariable Integer id,
            @RequestBody List<Integer> businessUnitIds,
            HttpServletRequest req) {
        var opOpt = opRepo.findById(id);
        if (opOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var resolved = BusinessUnitResolver.resolve(buRepo, businessUnitIds);
        if (resolved.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(
                            Map.of(
                                    "error",
                                    "Um ou mais IDs de Unidade de Negócio informados não existem."));
        }
        Operation op = opOpt.get();
        op.setBusinessUnits(resolved.get());
        Operation saved = opRepo.save(op);
        auditService.log(
                req,
                "MASTERDATA_UPDATE",
                "BUs da operação '" + saved.getName() + "' atualizadas (id=" + id + ")",
                true);
        return ResponseEntity.ok(saved);
    }

    /**
     * Sincroniza (substitui por completo) quais Clientes têm esta Operação vinculada. A relação é
     * dona pelo lado de {@code Client.operations} — aqui percorremos os clientes que precisam
     * ganhar ou perder o vínculo e salvamos cada um deles. Campo opcional — lista vazia é válida e
     * desvincula a operação de todos os clientes.
     */
    @PutMapping("/{id}/clients")
    @Transactional
    public ResponseEntity<?> syncOperationClients(
            @PathVariable Integer id,
            @RequestBody List<Integer> clientIds,
            HttpServletRequest req) {
        var opOpt = opRepo.findById(id);
        if (opOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Operation op = opOpt.get();

        List<Integer> ids = clientIds == null ? List.of() : clientIds;
        Set<Integer> desiredIds = Set.copyOf(ids);
        List<Client> desiredClients = clientRepo.findAllById(desiredIds);
        if (desiredClients.size() != desiredIds.size()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Um ou mais IDs de cliente informados não existem."));
        }

        // Remove o vínculo dos clientes que hoje têm a operação mas não estão na lista nova.
        for (Client client : new ArrayList<>(op.getClients())) {
            if (!desiredIds.contains(client.getId())) {
                client.getOperations().remove(op);
                clientRepo.save(client);
            }
        }

        // Adiciona o vínculo aos clientes da lista nova que ainda não o têm.
        for (Client client : desiredClients) {
            if (!client.getOperations().contains(op)) {
                client.getOperations().add(op);
                clientRepo.save(client);
            }
        }

        auditService.log(
                req,
                "MASTERDATA_UPDATE",
                "Clientes vinculados à operação '" + op.getName() + "' atualizados (id=" + id + ")",
                true);
        return ResponseEntity.noContent().build();
    }
}
