package com.asteriskia.domain.callcenter.interaction;

import com.asteriskia.domain.callcenter.copilot.ContactProfileView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CallCenterInteractionController — interação em curso do agente autenticado e tabulação
 * (Fase 4). RBAC via {@code PERM_READ_callcenter.desktop}/{@code PERM_WRITE_callcenter.desktop}.
 *
 * <p>GET /api/v1/callcenter/interactions/current — interação em atendimento pelo agente
 * autenticado (para o screen pop) GET /api/v1/callcenter/interactions/dispositions — catálogo de
 * tabulações ativas POST /api/v1/callcenter/interactions/disposition — aplica a tabulação e
 * encerra o ACW
 */
@RestController
@RequestMapping("/api/v1/callcenter/interactions")
@RequiredArgsConstructor
public class CallCenterInteractionController {

    private final CallCenterInteractionService service;
    private final CcDispositionRepository dispositionRepository;

    @GetMapping("/current")
    public ResponseEntity<InteractionView> current() {
        return ResponseEntity.ok(service.currentInteraction());
    }

    /** Fase 14 — histórico de contatos anteriores do mesmo contato identificado na interação
     * informada (screen pop). O {@code resolvedAdSam} usado na busca vem sempre da própria
     * interação {@code id}, carregada e validada contra o agente autenticado dentro do serviço
     * — nunca de um parâmetro do chamador (isso seria um IDOR, permitindo enumerar o histórico e
     * a identidade de qualquer contato do AD). */
    @GetMapping("/{id}/contact-history")
    public ResponseEntity<List<InteractionView>> contactHistory(@PathVariable Long id) {
        return ResponseEntity.ok(service.contactHistory(id));
    }

    /** Fase 16.1 — histórico unificado voz+chat do contato identificado (diferente de {@code
     * contact-history} acima, que é só voz e existe desde a Fase 14). Mesmas garantias
     * anti-IDOR. */
    @GetMapping("/{id}/contact-history-unified")
    public ResponseEntity<List<com.asteriskia.domain.callcenter.copilot.ContactHistoryItem>> unifiedContactHistory(
            @PathVariable Long id) {
        return ResponseEntity.ok(service.unifiedContactHistory(id));
    }

    /** Fase 16.2 — perfil do contato traçado por IA (copiloto), com as mesmas garantias
     * anti-IDOR do histórico acima. {@code status=GENERATING} sinaliza ao frontend continuar
     * fazendo polling — a geração nunca bloqueia esta requisição. */
    @GetMapping("/{id}/contact-profile")
    public ResponseEntity<ContactProfileView> contactProfile(@PathVariable Long id) {
        return ResponseEntity.ok(service.contactProfile(id));
    }

    public record ContactProfileFeedbackRequest(@NotNull Long profileId, int actionIndex, boolean useful) {}

    /** Fase 16.3 — feedback (útil/não útil) sobre uma ação sugerida do copiloto. */
    @PostMapping("/{id}/contact-profile/feedback")
    public ResponseEntity<Void> contactProfileFeedback(
            @PathVariable Long id, @Valid @RequestBody ContactProfileFeedbackRequest request) {
        service.submitContactProfileFeedback(id, request.profileId(), request.actionIndex(), request.useful());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/dispositions")
    public ResponseEntity<List<CcDisposition>> dispositions() {
        return ResponseEntity.ok(dispositionRepository.findByActiveTrue());
    }

    @PostMapping("/disposition")
    public ResponseEntity<InteractionView> applyDisposition(
            @Valid @RequestBody DispositionRequest request) {
        return ResponseEntity.ok(service.applyDisposition(request));
    }
}
