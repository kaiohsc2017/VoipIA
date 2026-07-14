package com.asteriskia.domain.masterdata;

import com.asteriskia.config.ResourceNotFoundException;
import com.asteriskia.domain.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
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
 * ClientController — CRUD de Clientes e seus vínculos N:N com Operações/Unidades de Negócio (Módulo
 * 2), extraído de MasterDataController (fase 10 da refatoração).
 *
 * <p>{@code @Transactional} em nível de classe: Client carrega businessUnits como coleção LAZY
 * (operations) e é serializado diretamente pelo Jackson — sem uma sessão Hibernate aberta durante a
 * serialização, o acesso lazy fora de transação lança LazyInitializationException
 * (spring.jpa.open-in-view=false neste projeto).
 */
@RestController
@RequestMapping("/api/v1/clients")
@RequiredArgsConstructor
@Transactional
public class ClientController {

    private final ClientRepository clientRepo;
    private final OperationRepository opRepo;
    private final BusinessUnitRepository buRepo;
    private final AuditService auditService;

    @GetMapping
    public ResponseEntity<List<Client>> listClients(
            @RequestParam(required = false) Boolean active) {
        List<Client> result =
                active != null ? clientRepo.findByIsActive(active) : clientRepo.findAll();
        return ResponseEntity.ok(
                MasterDataScopeFilter.filterByBusinessUnitScope(
                        result,
                        c -> c.getBusinessUnits().stream().map(BusinessUnit::getId).toList()));
    }

    @PostMapping
    public ResponseEntity<Client> createClient(
            @Valid @RequestBody Client client, HttpServletRequest req) {
        Client saved = clientRepo.save(client);
        auditService.log(
                req,
                "MASTERDATA_CREATE",
                "Cliente criado: '" + saved.getName() + "' (id=" + saved.getId() + ")",
                true);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Client> updateClient(
            @PathVariable Integer id, @Valid @RequestBody Client client, HttpServletRequest req) {
        client.setId(id);
        Client saved = clientRepo.save(client);
        auditService.log(
                req,
                "MASTERDATA_UPDATE",
                "Cliente atualizado: '" + saved.getName() + "' (id=" + id + ")",
                true);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteClient(@PathVariable Integer id, HttpServletRequest req) {
        auditService.log(req, "MASTERDATA_DELETE", "Cliente removido (id=" + id + ")", true);
        clientRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /** Vincula operação a cliente (N:N). */
    @PostMapping("/{clientId}/operations/{operationId}")
    @Transactional
    public ResponseEntity<Void> addOperation(
            @PathVariable Integer clientId,
            @PathVariable Integer operationId,
            HttpServletRequest req) {
        Client client =
                clientRepo
                        .findById(clientId)
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "Cliente não encontrado: " + clientId));
        Operation op =
                opRepo.findById(operationId)
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "Operação não encontrada: " + operationId));
        client.getOperations().add(op);
        clientRepo.save(client);
        auditService.log(
                req,
                "MASTERDATA_UPDATE",
                "Operação '" + op.getName() + "' vinculada ao cliente '" + client.getName() + "'",
                true);
        return ResponseEntity.noContent().build();
    }

    /** Lista operações disponíveis para um cliente. */
    @GetMapping("/{clientId}/operations")
    public ResponseEntity<List<Operation>> getClientOperations(@PathVariable Integer clientId) {
        Client client =
                clientRepo
                        .findById(clientId)
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "Cliente não encontrado: " + clientId));
        return ResponseEntity.ok(client.getOperations().stream().toList());
    }

    /**
     * Sincroniza (substitui por completo) as Unidades de Negócio vinculadas a um cliente. Campo
     * opcional — lista vazia é válida e limpa a associação.
     */
    @PutMapping("/{id}/business-units")
    public ResponseEntity<?> syncClientBusinessUnits(
            @PathVariable Integer id,
            @RequestBody List<Integer> businessUnitIds,
            HttpServletRequest req) {
        var clientOpt = clientRepo.findById(id);
        if (clientOpt.isEmpty()) {
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
        Client client = clientOpt.get();
        client.setBusinessUnits(resolved.get());
        Client saved = clientRepo.save(client);
        auditService.log(
                req,
                "MASTERDATA_UPDATE",
                "BUs do cliente '" + saved.getName() + "' atualizadas (id=" + id + ")",
                true);
        return ResponseEntity.ok(saved);
    }
}
