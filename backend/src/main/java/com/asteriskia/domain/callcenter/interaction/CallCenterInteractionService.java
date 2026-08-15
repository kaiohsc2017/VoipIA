package com.asteriskia.domain.callcenter.interaction;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.copilot.CallCenterContactHistoryService;
import com.asteriskia.domain.callcenter.copilot.CcContactProfile;
import com.asteriskia.domain.callcenter.copilot.CcContactProfileFeedback;
import com.asteriskia.domain.callcenter.copilot.CcContactProfileFeedbackRepository;
import com.asteriskia.domain.callcenter.copilot.CcContactProfileRepository;
import com.asteriskia.domain.callcenter.copilot.ContactHistoryItem;
import com.asteriskia.domain.callcenter.copilot.ContactProfileService;
import com.asteriskia.domain.callcenter.copilot.ContactProfileView;
import com.asteriskia.integration.ad.AdUserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * CallCenterInteractionService — leitura da interação em curso do agente (screen pop / painel de
 * atendimento) e aplicação de tabulação ao final da chamada (Fase 4).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallCenterInteractionService {

    private final CcInteractionRepository interactionRepository;
    private final CcDispositionRepository dispositionRepository;
    private final CallCenterAgentStateService agentStateService;
    private final AdUserRepository adUserRepository;
    private final ContactProfileService contactProfileService;
    private final CcContactProfileRepository contactProfileRepository;
    private final CcContactProfileFeedbackRepository contactProfileFeedbackRepository;
    private final CallCenterContactHistoryService contactHistoryService;

    /** Interação aberta (em atendimento) do agente autenticado, se houver. Fase 14: quando
     * {@code resolvedAdSam} está preenchido, o bloco de identidade é resolvido contra a cópia
     * local do AD ({@code ad_users}) — nunca ao vivo. */
    @Transactional(readOnly = true)
    public InteractionView currentInteraction() {
        var agent = agentStateService.currentAgent();
        return interactionRepository
                .findByAgentIdAndEndedAtIsNull(agent.getId())
                .map(this::toViewWithIdentity)
                .orElse(null);
    }

    /** Histórico de contatos anteriores do mesmo contato identificado (Fase 14, screen pop) —
     * exclui a interação atual. Lista vazia quando a interação atual não tem contato resolvido
     * ou não pertence ao agente autenticado. O {@code resolvedAdSam} usado na busca é sempre o
     * da própria interação carregada do banco — nunca um valor vindo do chamador (IDOR: um
     * {@code resolvedAdSam} arbitrário na query string permitiria a qualquer agente enumerar o
     * histórico e o bloco completo de identidade — nome/e-mail/telefone/cargo — de qualquer
     * contato do AD, não só do próprio atendimento em curso). */
    @Transactional(readOnly = true)
    public List<InteractionView> contactHistory(Long currentInteractionId) {
        var current = ownedInteractionWithResolvedSam(currentInteractionId).orElse(null);
        if (current == null) {
            return List.of();
        }
        return interactionRepository
                .findTop10ByResolvedAdSamAndIdNotOrderByQueuedAtDesc(
                        current.getResolvedAdSam(), currentInteractionId)
                .stream()
                .map(this::toViewWithIdentity)
                .toList();
    }

    /** Perfil de IA do copiloto (Fase 16.2) — mesma disciplina anti-IDOR de {@link
     * #contactHistory}: o {@code resolved_ad_sam} usado nunca vem do chamador, sempre da própria
     * interação {@code id}, já validada como do agente autenticado. */
    @Transactional(readOnly = true)
    public ContactProfileView contactProfile(Long currentInteractionId) {
        var current = ownedInteractionWithResolvedSam(currentInteractionId).orElse(null);
        if (current == null) {
            return ContactProfileView.unavailable();
        }
        return contactProfileService.getOrTrigger(current.getResolvedAdSam(), currentInteractionId);
    }

    /** Histórico unificado voz+chat (Fase 16.1) — mesma disciplina anti-IDOR de {@link
     * #contactHistory}, mas via {@link CallCenterContactHistoryService} (inclui sessões de chat,
     * diferente do {@code contactHistory} acima, que é só voz e existe desde a Fase 14). */
    @Transactional(readOnly = true)
    public List<ContactHistoryItem> unifiedContactHistory(Long currentInteractionId) {
        var current = ownedInteractionWithResolvedSam(currentInteractionId).orElse(null);
        if (current == null) {
            return List.of();
        }
        return contactHistoryService.historyFor(current.getResolvedAdSam(), 10, currentInteractionId, null);
    }

    /** Feedback do agente sobre uma ação sugerida do copiloto (Fase 16.3) — valida tanto a posse
     * da interação quanto que o {@code profileId} informado pertence de fato ao MESMO contato da
     * interação (nunca confia no par interação/perfil vindo do chamador sem essa checagem cruzada
     * — mesma classe de IDOR já corrigida no histórico de contato da Fase 14). */
    @Transactional
    public void submitContactProfileFeedback(
            Long currentInteractionId, Long profileId, int actionIndex, boolean useful) {
        var current = ownedInteractionWithResolvedSam(currentInteractionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Interação não encontrada."));
        CcContactProfile profile = contactProfileRepository
                .findById(profileId)
                .filter(p -> p.getResolvedAdSam().equals(current.getResolvedAdSam()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Perfil não encontrado."));
        var agent = agentStateService.currentAgent();
        contactProfileFeedbackRepository.save(
                CcContactProfileFeedback.builder()
                        .profileId(profile.getId())
                        .actionIndex(actionIndex)
                        .useful(useful)
                        .agentId(agent.getId())
                        .build());
    }

    /** Carrega a interação {@code id} e retorna vazio a menos que ela pertença ao agente
     * autenticado e já tenha um contato identificado (Fase 14) — base comum de {@link
     * #contactHistory} e {@link #contactProfile}. */
    private java.util.Optional<CcInteraction> ownedInteractionWithResolvedSam(Long interactionId) {
        var agent = agentStateService.currentAgent();
        return interactionRepository
                .findById(interactionId)
                .filter(i -> i.getAgent() != null && i.getAgent().getId().equals(agent.getId()))
                .filter(i -> i.getResolvedAdSam() != null && !i.getResolvedAdSam().isBlank());
    }

    private InteractionView toViewWithIdentity(CcInteraction interaction) {
        if (interaction.getResolvedAdSam() == null) {
            return InteractionView.from(interaction);
        }
        var adUser = adUserRepository.findBySamAccountNameIgnoreCase(interaction.getResolvedAdSam()).orElse(null);
        return InteractionView.from(interaction, adUser);
    }

    /**
     * Aplica a tabulação à última interação encerrada e ainda não tabulada do agente autenticado,
     * e encerra o ACW (agente volta para DISPONIVEL). Rejeita se não houver interação pendente de
     * tabulação — evita tabular a chamada errada por uma corrida entre duas abas do mesmo agente.
     */
    @Transactional
    public InteractionView applyDisposition(CcAgent agent, Long dispositionId) {
        var interaction =
                interactionRepository
                        .findFirstByAgentIdAndEndedAtIsNotNullAndDispositionIsNullOrderByEndedAtDesc(
                                agent.getId())
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Nenhuma interação pendente de tabulação para este agente."));
        var disposition =
                dispositionRepository
                        .findById(dispositionId)
                        .filter(CcDisposition::getActive)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Tabulação inválida: " + dispositionId));

        interaction.setDisposition(disposition);
        var saved = interactionRepository.save(interaction);

        agentStateService.setState(agent, AgentState.DISPONIVEL, null);
        log.info(
                "Interação tabulada: interactionId={} agentId={} disposition={}",
                saved.getId(),
                agent.getId(),
                disposition.getCode());
        return InteractionView.from(saved);
    }

    @Transactional
    public InteractionView applyDisposition(DispositionRequest request) {
        return applyDisposition(agentStateService.currentAgent(), request.dispositionId());
    }
}
