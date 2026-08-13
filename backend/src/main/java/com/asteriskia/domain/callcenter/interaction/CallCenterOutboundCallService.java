package com.asteriskia.domain.callcenter.interaction;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.CcExtension;
import com.asteriskia.domain.callcenter.CcExtensionRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CallCenterOutboundCallService — chamada de saída ativo manual (Fase 23 do plano omnicanal
 * Parte III). Diferente do receptivo ({@link CallCenterAmiEventListener}, alimentado por eventos
 * AMI de fila), o início/fim de uma chamada de saída chega via CURL do próprio dialplan
 * (contexto {@code _X.} em {@code ramais-internos}) — mesmo padrão já usado por
 * {@code CallCenterRecordingService}/{@code UraRoutingController}, e não pelo listener AMI: os
 * nomes de campo AMI de canal (Newchannel/DialBegin) nunca foram validados contra este Asterisk,
 * enquanto o dialplan já sabe com certeza o UNIQUEID, o ramal de origem e o número discado.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallCenterOutboundCallService {

    private final CcExtensionRepository extensionRepository;
    private final CcInteractionRepository interactionRepository;
    private final CcInteractionEventRepository interactionEventRepository;
    private final CallCenterAgentStateService agentStateService;

    /** Chamado pelo dialplan ao entrar no contexto de saída, antes do {@code Dial()}. Ramal que
     * não pertence a nenhum agente de Call Center (ex: ramal de teste 1001/1002 discando) é
     * silenciosamente ignorado — não é erro, só não gera {@code cc_interactions}. */
    @Transactional
    public void start(String channelUniqueId, String agentExtension, String dialedNumber) {
        if (channelUniqueId == null || interactionRepository.existsByChannelUniqueId(channelUniqueId)) {
            return;
        }
        CcAgent agent = extensionRepository.findByExtension(agentExtension)
                .map(CcExtension::getAgent)
                .orElse(null);
        if (agent == null) {
            log.debug(
                    "Chamada de saída de ramal sem agente de Call Center vinculado (extension={}) — não registrada.",
                    agentExtension);
            return;
        }
        CcInteraction interaction =
                interactionRepository.save(
                        CcInteraction.builder()
                                .direction(Direction.OUTBOUND)
                                .agent(agent)
                                .businessUnit(agent.getBusinessUnit())
                                .channelUniqueId(channelUniqueId)
                                .ani(dialedNumber)
                                .queuedAt(LocalDateTime.now())
                                .build());
        recordEvent(interaction, "OutboundStart", agentExtension, dialedNumber);
        agentStateService.setState(agent, AgentState.EM_ATENDIMENTO, null);
    }

    /** Chamado pelo dialplan após o {@code Dial()} retornar (atendida, ocupado, sem resposta ou
     * cancelada), antes do {@code Hangup()} final. {@code answeredSeconds} chega como
     * {@code String} — o dialplan manda {@code ${ANSWEREDTIME}}, que o Asterisk só preenche em
     * chamada atendida; em BUSY/NOANSWER/CANCEL/CONGESTION o parâmetro chega presente e VAZIO
     * (não ausente), o que quebraria o binding automático para {@code Integer} no controller
     * antes de chegar aqui — o parsing defensivo mora neste método. */
    @Transactional
    public void end(String channelUniqueId, String dialStatus, String answeredSeconds) {
        interactionRepository
                .findByChannelUniqueId(channelUniqueId)
                .ifPresentOrElse(
                        interaction -> finishInteraction(interaction, dialStatus, parseAnsweredSeconds(answeredSeconds)),
                        () ->
                                log.debug(
                                        "Fim de chamada de saída sem interação correspondente (channelUniqueId={}) — ignorado.",
                                        channelUniqueId));
    }

    private Integer parseAnsweredSeconds(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log.debug("answeredSeconds inválido recebido do dialplan ({}) — tratado como não atendida.", raw);
            return null;
        }
    }

    private void finishInteraction(CcInteraction interaction, String dialStatus, Integer answeredSeconds) {
        LocalDateTime endedAt = LocalDateTime.now();
        boolean answered = "ANSWER".equalsIgnoreCase(dialStatus) && answeredSeconds != null && answeredSeconds > 0;
        interaction.setEndedAt(endedAt);
        if (answered) {
            // Clamp defensivo: answeredSeconds vem de uma variável de canal do Asterisk, dado
            // externo não confiável — sem isso, um valor absurdo produziria answeredAt antes de
            // queuedAt (chamada "atendida antes de começar"), corrompendo TMA em silêncio.
            LocalDateTime answeredAt = endedAt.minusSeconds(answeredSeconds);
            LocalDateTime queuedAt = interaction.getQueuedAt();
            interaction.setAnsweredAt(
                    queuedAt != null && answeredAt.isBefore(queuedAt) ? queuedAt : answeredAt);
        }
        interactionRepository.save(interaction);
        recordEvent(interaction, "OutboundEnd", dialStatus, String.valueOf(answeredSeconds));

        CcAgent agent = interaction.getAgent();
        if (agent == null) {
            return;
        }
        // Atendida: mesmo ACW do receptivo (agente tabula antes de voltar a Disponível). Não
        // atendida (ocupado/sem resposta/cancelada): nada a tabular, volta direto a Disponível.
        agentStateService.setState(agent, answered ? AgentState.ACW : AgentState.DISPONIVEL, null);
    }

    private void recordEvent(CcInteraction interaction, String type, String detailA, String detailB) {
        interactionEventRepository.save(
                CcInteractionEvent.builder()
                        .interaction(interaction)
                        .eventType(type)
                        .details(detailA + " / " + detailB)
                        .build());
    }
}
