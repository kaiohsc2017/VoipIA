package com.asteriskia.domain.callcenter.cobrowsing;

import com.asteriskia.domain.audit.AuditService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
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
 * CallCenterCobrowsingController — player/administração das sessões de co-browsing gravado do
 * chat (Fase 17, sub-fase 17c). RBAC via {@code PERM_READ_callcenter.cobrowsing}/
 * {@code PERM_WRITE_callcenter.cobrowsing} (SecurityConfig) — recurso próprio, não reusa
 * {@code callcenter.gravacoes} (decisão do plano §6).
 *
 * <p>GET    /api/v1/callcenter/cobrowsing              — lista paginada (escopo de BU)
 * <p>GET    /api/v1/callcenter/cobrowsing/{id}/events   — eventos rrweb já descomprimidos (JSON)
 * <p>DELETE /api/v1/callcenter/cobrowsing/{id}          — eliminação sob demanda (§5.5, antecipada)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/callcenter/cobrowsing")
@RequiredArgsConstructor
public class CallCenterCobrowsingController {

    private final CallCenterCobrowsingService service;
    private final CallCenterCobrowseRetentionService retentionService;
    private final AuditService auditService;

    /** GET /api/v1/callcenter/cobrowsing/retention-config — Fase 17d. */
    @GetMapping("/retention-config")
    public ResponseEntity<CobrowseRetentionConfigView> getRetentionConfig() {
        return ResponseEntity.ok(retentionService.getConfig());
    }

    /** PUT /api/v1/callcenter/cobrowsing/retention-config — Fase 17d. */
    @PutMapping("/retention-config")
    public ResponseEntity<CobrowseRetentionConfigView> updateRetentionConfig(
            @Valid @RequestBody CobrowseRetentionConfigRequest request, Authentication auth) {
        return ResponseEntity.ok(retentionService.updateConfig(request, auth.getName()));
    }

    /** POST /api/v1/callcenter/cobrowsing/retention-config/run — disparo manual (Fase 17d). */
    @PostMapping("/retention-config/run")
    public ResponseEntity<CobrowseRetentionRunResult> runRetentionNow() {
        return ResponseEntity.ok(retentionService.purgeExpired());
    }

    @GetMapping
    public ResponseEntity<Page<CcCobrowseSession>> list(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "startedAt"));
        return ResponseEntity.ok(service.list(pageable));
    }

    /**
     * 404 (nunca 403) para: id inexistente, sessão fora do escopo de BU do usuário,
     * {@code consentStatus != granted} (revogado/negado nunca é servido, mesmo que o arquivo
     * ainda exista fisicamente — guarda de negócio, não só de storage), {@code filePath} nulo
     * (nunca houve captura) ou arquivo físico ausente/ilegível. Toda reprodução bem-sucedida é
     * auditada, mesmo padrão de {@code CallCenterRecordingController#audio}.
     */
    @GetMapping("/{id}/events")
    public ResponseEntity<List<JsonNode>> events(@PathVariable Long id, HttpServletRequest request) {
        var sessionOpt = service.findByIdInScope(id);
        if (sessionOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var session = sessionOpt.get();
        if (!"granted".equals(session.getConsentStatus()) || session.getFilePath() == null) {
            return ResponseEntity.notFound().build();
        }

        List<JsonNode> events = service.readEvents(session);
        if (events == null) {
            log.warn("Arquivo de co-browsing não encontrado/ilegível para id={}", id);
            return ResponseEntity.notFound().build();
        }

        auditService.log(
                request,
                "callcenter.cobrowsing.play",
                "Sessão de co-browsing id=" + id + " reproduzida",
                true);
        return ResponseEntity.ok(events);
    }

    /**
     * Eliminação sob demanda — apaga o arquivo físico se existir e marca {@code purged_at},
     * nunca a linha do banco (mesmo padrão de retenção de gravação de voz). 404 (nunca 403) para
     * id inexistente ou fora do escopo de BU. Idempotente — chamar duas vezes não lança.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        var sessionOpt = service.findByIdInScope(id);
        if (sessionOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        service.purge(sessionOpt.get());
        auditService.log(
                request,
                "callcenter.cobrowsing.purge",
                "Sessão de co-browsing id=" + id + " eliminada sob demanda",
                true);
        return ResponseEntity.noContent().build();
    }
}
