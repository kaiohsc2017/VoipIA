package com.asteriskia.domain.callcenter.flow;

import com.asteriskia.domain.audit.AuditService;
import com.asteriskia.domain.callcenter.flow.simulation.FlowSimulationRequest;
import com.asteriskia.domain.callcenter.flow.simulation.FlowSimulationResult;
import com.asteriskia.domain.callcenter.flow.simulation.FlowSimulationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CallCenterFlowController — CRUD, versionamento e rollback dos fluxos do Flow Builder (Fase 5a).
 * RBAC via {@code PERM_READ_callcenter.fluxos}/{@code PERM_WRITE_callcenter.fluxos}.
 *
 * <p>GET /api/v1/callcenter/fluxos — lista (escopo por BU) GET .../{id} — detalhe GET
 * .../catalogo — catálogo de tipos de nó (fonte única, também servido ao editor) POST — cria
 * fluxo + primeiro rascunho vazio PUT .../{id} — atualiza metadado PUT .../{id}/draft — salva o
 * grafo do rascunho POST .../{id}/publish — publica o rascunho (bloqueia nó não implementado)
 * POST .../{id}/simulate — simula o fluxo em dry-run (Fase 5d, nunca persiste execução real) POST
 * .../{id}/rollback/{versionId} — volta a versão arquivada a publicada GET .../{id}/versions
 * — histórico (sem o grafo) GET .../{id}/versions/{versionId} — detalhe de uma versão (com grafo)
 * DELETE .../{id} — remove fluxo sem versão publicada
 */
@RestController
@RequestMapping("/api/v1/callcenter/fluxos")
@RequiredArgsConstructor
public class CallCenterFlowController {

    private final CallCenterFlowService service;
    private final FlowGraphNodeCatalog nodeCatalog;
    private final AuditService auditService;
    private final FlowSimulationService simulationService;

    @GetMapping
    public ResponseEntity<List<FlowView>> getAll() {
        return ResponseEntity.ok(service.findAll().stream().map(FlowView::from).toList());
    }

    @GetMapping("/catalogo")
    public ResponseEntity<List<FlowGraphNodeType>> catalog() {
        return ResponseEntity.ok(nodeCatalog.all());
    }

    @GetMapping("/{id}")
    public ResponseEntity<FlowView> getById(@PathVariable Long id) {
        return ResponseEntity.ok(FlowView.from(service.findById(id)));
    }

    @PostMapping
    public ResponseEntity<FlowView> create(@Valid @RequestBody FlowRequest request, HttpServletRequest httpRequest) {
        var flow = service.create(request);
        auditService.log(httpRequest, "CALLCENTER_FLOW_CREATE", "Fluxo criado: " + flow.getName(), true);
        return ResponseEntity.status(HttpStatus.CREATED).body(FlowView.from(flow));
    }

    @PutMapping("/{id}")
    public ResponseEntity<FlowView> update(
            @PathVariable Long id, @Valid @RequestBody FlowRequest request, HttpServletRequest httpRequest) {
        var flow = service.update(id, request);
        auditService.log(httpRequest, "CALLCENTER_FLOW_UPDATE", "Fluxo atualizado: " + flow.getName(), true);
        return ResponseEntity.ok(FlowView.from(flow));
    }

    @PutMapping("/{id}/draft")
    public ResponseEntity<FlowGraphValidationResult> saveDraft(
            @PathVariable Long id, @Valid @RequestBody DraftSaveRequest request, HttpServletRequest httpRequest) {
        var result = service.saveDraft(id, request.graph());
        auditService.log(httpRequest, "CALLCENTER_FLOW_DRAFT_SAVE", "Rascunho salvo do fluxo " + id, true);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<FlowGraphValidationResult> publish(@PathVariable Long id, HttpServletRequest httpRequest) {
        var result = service.publish(id);
        auditService.log(
                httpRequest, "CALLCENTER_FLOW_PUBLISH", "Fluxo " + id + " publicado=" + result.isValid(), result.isValid());
        return ResponseEntity.ok(result);
    }

    /** Simulador (Fase 5d, dry-run) — nunca persiste {@code cc_flow_executions}/
     * {@code cc_flow_execution_steps} nem chama IA real (ver {@code FlowSimulationService}). */
    @PostMapping("/{id}/simulate")
    public ResponseEntity<FlowSimulationResult> simulate(
            @PathVariable Long id, @RequestBody(required = false) FlowSimulationRequest request) {
        var result = simulationService.simulate(id, request == null ? FlowSimulationRequest.empty() : request);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{id}/rollback/{versionId}")
    public ResponseEntity<Void> rollback(
            @PathVariable Long id, @PathVariable Long versionId, HttpServletRequest httpRequest) {
        service.rollback(id, versionId);
        auditService.log(
                httpRequest, "CALLCENTER_FLOW_ROLLBACK", "Fluxo " + id + " voltou para a versão " + versionId, true);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/versions")
    public ResponseEntity<List<FlowVersionView>> listVersions(@PathVariable Long id) {
        return ResponseEntity.ok(
                service.listVersions(id).stream().map(v -> FlowVersionView.from(v, false)).toList());
    }

    @GetMapping("/{id}/versions/{versionId}")
    public ResponseEntity<FlowVersionView> getVersion(@PathVariable Long id, @PathVariable Long versionId) {
        return ResponseEntity.ok(FlowVersionView.from(service.findVersion(id, versionId), true));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, HttpServletRequest httpRequest) {
        service.delete(id);
        auditService.log(httpRequest, "CALLCENTER_FLOW_DELETE", "Fluxo " + id + " excluído", true);
        return ResponseEntity.noContent().build();
    }
}
