package com.asteriskia.domain.callcenter;

import com.asteriskia.domain.audit.AuditService;
import com.asteriskia.domain.callcenter.interaction.CallCenterAgentStateService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * CallCenterAgentController — CRUD de agentes/ramais do Call Center (Fase 2) + filas do agente
 * (Fase 12.4) + credencial SIP do próprio agente (Fase 13).
 *
 * GET    /api/v1/callcenter/agentes                              — lista (escopo por BU)
 * GET    /api/v1/callcenter/agentes/{id}                          — detalhe
 * POST   /api/v1/callcenter/agentes                               — cria agente + provisiona ramal ARA
 * PUT    /api/v1/callcenter/agentes/{id}                          — atualiza dados do agente
 * DELETE /api/v1/callcenter/agentes/{id}                          — remove agente + desprovisiona ramal ARA
 * GET    /api/v1/callcenter/agentes/{id}/ramal-secret              — senha do ramal (callcenter.ramais)
 * GET    /api/v1/callcenter/agentes/me/sip-credentials             — credencial SIP do próprio agente (callcenter.desktop)
 * POST   /api/v1/callcenter/agentes/{id}/rotate-secret             — rotaciona o secret SIP (callcenter.ramais)
 * GET    /api/v1/callcenter/agentes/{id}/filas                     — filas do agente
 * POST   /api/v1/callcenter/agentes/{id}/filas/{queueId}           — adiciona a uma fila (body opcional {penalty})
 * PUT    /api/v1/callcenter/agentes/{id}/filas/{queueId}/prioridade — atualiza a prioridade
 * DELETE /api/v1/callcenter/agentes/{id}/filas/{queueId}           — remove de uma fila
 */
@RestController
@RequestMapping("/api/v1/callcenter/agentes")
@RequiredArgsConstructor
public class CallCenterAgentController {

    private final CallCenterAgentService service;
    // A lógica de fila vive só em CallCenterQueueService — este controller é fachada, mesmo
    // padrão já usado pelos endpoints /filas/{id}/membros de CallCenterQueueController.
    private final CallCenterQueueService queueService;
    private final CallCenterAgentStateService agentStateService;
    private final AuditService auditService;
    private final SipCredentialsRateLimiter rateLimiter;

    @GetMapping
    public ResponseEntity<List<CcAgent>> getAll() {
        return ResponseEntity.ok(service.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<CcAgent> getById(@PathVariable Long id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @PostMapping
    public ResponseEntity<CcAgent> create(@Valid @RequestBody AgentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CcAgent> update(@PathVariable Long id, @Valid @RequestBody AgentRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/ramal-secret")
    public ResponseEntity<Map<String, String>> ramalSecret(@PathVariable Long id) {
        return ResponseEntity.ok(Map.of("secret", service.extensionSecret(id)));
    }

    /**
     * Credencial SIP do agente do usuário logado (Fase 13, D9-A) — nunca aceita id de outro
     * agente. Auditada a cada leitura ({@code AuditService}) e limitada em frequência
     * ({@code SipCredentialsRateLimiter}) por ser, de fato, uma credencial que circula ao
     * browser — mesmo assim é estritamente melhor que a senha única global do bundle que existia
     * antes desta fase.
     */
    @GetMapping("/me/sip-credentials")
    public ResponseEntity<CallCenterAgentService.SipCredentials> mySipCredentials(HttpServletRequest request) {
        var username = SecurityContextHolder.getContext().getAuthentication().getName();
        if (!rateLimiter.allow(username)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Muitas requisições — aguarde.");
        }
        var agent = agentStateService.currentAgent();
        var credentials = service.sipCredentialsOf(agent);
        auditService.log(request, "CALLCENTER_SIP_CREDENTIALS_READ", "Ramal " + credentials.extension(), true);
        return ResponseEntity.ok(credentials);
    }

    /** Rotaciona o secret SIP do ramal (Fase 13, D9-A) — a credencial passou a circular ao
     * browser, então precisa ser rotacionável. RBAC restrito a {@code callcenter.ramais} (mesmo
     * resource de {@code ramal-secret}), não {@code callcenter.desktop} — rotacionar afeta o
     * registro em curso do agente, não é uma ação do próprio agente sobre si mesmo. */
    @PostMapping("/{id}/rotate-secret")
    public ResponseEntity<Void> rotateSecret(@PathVariable Long id, HttpServletRequest request) {
        service.rotateExtensionSecret(id);
        auditService.log(request, "CALLCENTER_SIP_SECRET_ROTATED", "Agente id=" + id, true);
        return ResponseEntity.noContent().build();
    }

    public record QueueMemberBody(@PositiveOrZero Integer penalty) {}

    @GetMapping("/{id}/filas")
    public ResponseEntity<List<CcQueueMember>> queues(@PathVariable Long id) {
        return ResponseEntity.ok(queueService.queuesOfAgent(id));
    }

    @PostMapping("/{id}/filas/{queueId}")
    public ResponseEntity<CcQueueMember> addToQueue(
            @PathVariable Long id, @PathVariable Long queueId,
            @RequestBody(required = false) QueueMemberBody body) {
        int penalty = body != null && body.penalty() != null ? body.penalty() : 0;
        return ResponseEntity.status(HttpStatus.CREATED).body(queueService.addMember(queueId, id, penalty));
    }

    @PutMapping("/{id}/filas/{queueId}/prioridade")
    public ResponseEntity<CcQueueMember> updateQueuePriority(
            @PathVariable Long id, @PathVariable Long queueId, @Valid @RequestBody QueueMemberBody body) {
        if (body.penalty() == null) {
            throw new IllegalArgumentException("Prioridade é obrigatória.");
        }
        return ResponseEntity.ok(queueService.updateMemberPenalty(queueId, id, body.penalty()));
    }

    @DeleteMapping("/{id}/filas/{queueId}")
    public ResponseEntity<Void> removeFromQueue(@PathVariable Long id, @PathVariable Long queueId) {
        queueService.removeMember(queueId, id);
        return ResponseEntity.noContent().build();
    }
}
