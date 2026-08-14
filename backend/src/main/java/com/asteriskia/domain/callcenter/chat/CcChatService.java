package com.asteriskia.domain.callcenter.chat;

import com.asteriskia.config.ResourceNotFoundException;
import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.CcQueueRepository;
import com.asteriskia.domain.callcenter.interaction.AgentState;
import com.asteriskia.domain.callcenter.interaction.CallCenterAgentStateService;
import com.asteriskia.domain.callcenter.interaction.CcDisposition;
import com.asteriskia.domain.callcenter.interaction.CcDispositionRepository;
import com.asteriskia.domain.callcenter.cobrowsing.CobrowseConsentService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/**
 * CcChatService — roteamento e ciclo de vida de uma sessão de chat (Fase 7a — base interna, sem
 * widget público ainda). Reaproveita {@link CallCenterAgentStateService#currentAgent()} pra
 * resolver o agente autenticado e {@link CcDisposition} (catálogo global de tabulação, já usado
 * por voz) pra encerrar sessões — nenhuma lógica nova de identidade/tabulação é criada aqui.
 *
 * <p>Modelo de roteamento desta fatia: "claim" explícito (o agente puxa uma sessão da fila) — não
 * é o motor de distribuição automática (ringall) usado em voz via ARI/Stasis (Fase 5b). Blending
 * (limite de chats simultâneos, precedência voz×chat) é escopo da Fase 7 completa, não desta
 * fatia — aqui a única gate é {@code AgentState.DISPONIVEL}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CcChatService {

    private final CcChatChannelRepository channelRepository;
    private final CcChatSessionRepository sessionRepository;
    private final CcChatMessageRepository messageRepository;
    private final CcQueueRepository queueRepository;
    private final CcDispositionRepository dispositionRepository;
    private final CallCenterAgentStateService agentStateService;
    private final SimpMessagingTemplate messagingTemplate;
    private final ChatTranscriptExportService transcriptExportService;
    private final ApplicationEventPublisher eventPublisher;
    private final CobrowseConsentService cobrowseConsentService;

    @Transactional
    public CcChatSession startSession(String channelCode, Long queueId, String customerRef, String customerName) {
        CcChatChannel channel = channelRepository.findByCodeAndActiveTrue(channelCode)
                .orElseThrow(() -> new ResourceNotFoundException("Canal de chat inválido ou inativo: " + channelCode));
        CcQueue queue = queueRepository.findById(queueId)
                .orElseThrow(() -> new ResourceNotFoundException("Fila não encontrada: " + queueId));
        return startSession(channel, queue, customerRef, customerName);
    }

    /** Fase 24: resolve a fila padrão do próprio canal (substitui a variável de ambiente única
     * que o widget interno usava) — 503 claro, nunca 500, quando o canal não tem fila configurada. */
    @Transactional
    public CcChatSession startSession(String channelCode, String customerRef, String customerName) {
        CcChatChannel channel = channelRepository.findByCodeAndActiveTrue(channelCode)
                .orElseThrow(() -> new ResourceNotFoundException("Canal de chat inválido ou inativo: " + channelCode));
        if (channel.getDefaultQueue() == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Canal de chat sem fila padrão configurada.");
        }
        return startSession(channel, channel.getDefaultQueue(), customerRef, customerName);
    }

    private CcChatSession startSession(CcChatChannel channel, CcQueue queue, String customerRef, String customerName) {
        if (customerRef == null || customerRef.isBlank()) {
            throw new IllegalArgumentException("customerRef é obrigatório.");
        }
        boolean hasBotFlow = channel.getBotFlow() != null && Boolean.TRUE.equals(channel.getBotFlow().getActive());

        CcChatSession session = sessionRepository.save(CcChatSession.builder()
                .channel(channel)
                .queue(queue)
                .businessUnit(queue.getBusinessUnit())
                .customerRef(customerRef)
                .customerName(customerName)
                .status(hasBotFlow ? "bot" : "waiting")
                .startedAt(LocalDateTime.now())
                .build());

        if (hasBotFlow) {
            eventPublisher.publishEvent(new ChatBotSessionStartedEvent(session.getId(), channel.getBotFlow().getId()));
        } else {
            messagingTemplate.convertAndSend("/topic/callcenter/chat/queue/" + queue.getId(),
                    new ChatQueueEvent(session.getId(), session.getCustomerName(), session.getStartedAt()));
        }
        log.info(
                "Sessão de chat iniciada: id={} canal={} fila={} bot={}",
                session.getId(), channel.getCode(), queue.getId(), hasBotFlow);
        return session;
    }

    @Transactional
    public CcChatSession claim(Long sessionId) {
        CcAgent agent = agentStateService.currentAgent();
        if (agentStateService.currentState(agent).state() != AgentState.DISPONIVEL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Só é possível assumir um chat estando Disponível.");
        }

        CcChatSession session = findSessionOrThrow(sessionId);
        if (!"waiting".equals(session.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Esta conversa já foi assumida por outro agente.");
        }

        session.setStatus("active");
        session.setAssignedAgent(agent);
        session.setClaimedAt(LocalDateTime.now());
        sessionRepository.save(session);

        // Fase 17a — disparo automático (D17-14): só cria o registro de co-browsing (ainda sem
        // captura real, 17b) se o agente tiver o toggle ligado; sem isso, nada acontece aqui.
        cobrowseConsentService.ensureSessionForClaim(session, agent);

        messagingTemplate.convertAndSend("/topic/callcenter/chat/queue/" + session.getQueue().getId(),
                new ChatQueueEvent(session.getId(), null, null));
        messagingTemplate.convertAndSend("/topic/callcenter/chat/session/" + session.getId(), toView(session));
        log.info("Sessão de chat assumida: id={} agentId={}", sessionId, agent.getId());
        return session;
    }

    @Transactional
    public CcChatMessage postMessage(Long sessionId, String senderType, String senderName, String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Mensagem vazia.");
        }
        CcChatSession session = findSessionOrThrow(sessionId);

        // senderName é resolvido aqui, nunca confiado do chamador, para "agent"/"system" —
        // só o simulador de cliente (senderType="customer") pode informar um nome livre.
        String resolvedSenderName = senderName;
        switch (senderType) {
            case "agent" -> {
                CcAgent agent = agentStateService.currentAgent();
                if (!"active".equals(session.getStatus())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta conversa não está ativa.");
                }
                if (session.getAssignedAgent() == null || !session.getAssignedAgent().getId().equals(agent.getId())) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Você não é o agente responsável por esta conversa.");
                }
                resolvedSenderName = agent.getName();
            }
            case "customer" -> {
                if (List.of("bot", "waiting", "active").stream().noneMatch(s -> s.equals(session.getStatus()))) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta conversa já foi encerrada.");
                }
                resolvedSenderName = senderName != null ? senderName : session.getCustomerName();
            }
            case "system" -> resolvedSenderName = "Sistema";
            default -> throw new IllegalArgumentException("senderType inválido: " + senderType);
        }

        CcChatMessage message = messageRepository.save(CcChatMessage.builder()
                .sessionId(sessionId)
                .senderType(senderType)
                .senderName(resolvedSenderName)
                .body(body)
                .build());

        messagingTemplate.convertAndSend("/topic/callcenter/chat/session/" + sessionId, toView(session));
        if ("customer".equals(senderType)) {
            // Sem custo se ninguém estiver ouvindo — o listener (Fase 24) só age quando há um
            // ChatChannelDriver registrado para esta sessão (bot em execução).
            eventPublisher.publishEvent(new ChatCustomerMessageReceivedEvent(sessionId, body));
        }
        return message;
    }

    /** Mensagem automática do motor de fluxo (nó tocar_audio/menu_opcoes em canal chat, Fase 24)
     * — nunca chamado a partir de um controller, só do driver de chat, por isso não passa pelas
     * checagens de {@code currentAgent()}/status de {@link #postMessage} (roda numa thread sem
     * autenticação de staff). Guarda contra sessão que não está mais em execução de bot (ex.: um
     * ADMIN encerrou manualmente enquanto a thread do fluxo ainda esperava resposta do cliente) —
     * sem essa guarda, a mensagem do bot era gravada e publicada por STOMP numa conversa já
     * fechada/tabulada/exportada, corrompendo a garantia de que "encerrada" é definitivo. */
    @Transactional
    public CcChatMessage postBotMessage(Long sessionId, String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Mensagem do bot vazia.");
        }
        CcChatSession session = findSessionOrThrow(sessionId);
        if (!"bot".equals(session.getStatus())) {
            log.warn("Bot tentou postar mensagem na sessão {} que não está mais em execução de bot (status={}) — ignorando.",
                    sessionId, session.getStatus());
            return null;
        }
        CcChatMessage message = messageRepository.save(CcChatMessage.builder()
                .sessionId(sessionId)
                .senderType("bot")
                .senderName("Assistente")
                .body(body)
                .build());
        messagingTemplate.convertAndSend("/topic/callcenter/chat/session/" + sessionId, toView(session));
        return message;
    }

    /** Fim da execução do bot com transferência para fila humana (nó "enviar_fila", Fase 24) —
     * equivalente chat de {@code ChannelDriver.transferToQueue}. {@code queueName} é o mesmo
     * {@code CcQueue.name} usado pelo nó de voz (resolvido pelo handler antes de chamar o
     * driver — aqui só resolvemos de novo pelo nome, sem duplicar a lógica de escolha da fila). */
    @Transactional
    public void transferToHumanQueue(Long sessionId, String queueName) {
        CcChatSession session = findSessionOrThrow(sessionId);
        if (!"bot".equals(session.getStatus())) {
            log.warn("Bot tentou transferir a sessão {} que não está mais em execução de bot (status={}) — ignorando.",
                    sessionId, session.getStatus());
            return;
        }
        CcQueue queue = queueRepository.findByName(queueName)
                .orElseThrow(() -> new ResourceNotFoundException("Fila não encontrada: " + queueName));
        session.setQueue(queue);
        session.setStatus("waiting");
        sessionRepository.save(session);
        messagingTemplate.convertAndSend("/topic/callcenter/chat/queue/" + queue.getId(),
                new ChatQueueEvent(session.getId(), session.getCustomerName(), session.getStartedAt()));
        messagingTemplate.convertAndSend("/topic/callcenter/chat/session/" + sessionId, toView(session));
        log.info("Bot transferiu sessão de chat {} para a fila {}.", sessionId, queue.getId());
    }

    /** Encerramento pelo próprio bot (nó "encerrar", Fase 24) — sem agente/tabulação, diferente
     * de {@link #close}. */
    @Transactional
    public void closeByBot(Long sessionId) {
        CcChatSession session = findSessionOrThrow(sessionId);
        if (!"bot".equals(session.getStatus())) {
            // Já fechada, ou saiu do controle do bot por outra via (ex.: ChatSessionEndedEvent) —
            // idempotente, nada a fazer.
            return;
        }
        session.setStatus("closed");
        session.setClosedAt(LocalDateTime.now());
        sessionRepository.save(session);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    transcriptExportService.export(sessionId);
                }
            });
        } else {
            transcriptExportService.export(sessionId);
        }
        messagingTemplate.convertAndSend("/topic/callcenter/chat/session/" + sessionId, toView(session));
        log.info("Sessão de chat {} encerrada pelo bot.", sessionId);
    }

    @Transactional
    public CcChatSession close(Long sessionId, Long dispositionId) {
        CcChatSession session = findSessionOrThrow(sessionId);
        assertOwnerOrAdmin(session);

        CcDisposition disposition = null;
        if (dispositionId != null) {
            disposition = dispositionRepository.findById(dispositionId)
                    .orElseThrow(() -> new ResourceNotFoundException("Tabulação não encontrada: " + dispositionId));
        }

        session.setStatus("closed");
        session.setClosedAt(LocalDateTime.now());
        session.setDisposition(disposition);
        sessionRepository.save(session);

        // Transcript exportado só depois do commit desta transação — a exportação carrega a
        // sessão de novo (já "closed" no banco), evitando que o UPDATE dela sobrescreva o
        // status/closedAt com um snapshot obtido antes deste commit (Fase 11 do plano omnicanal).
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    transcriptExportService.export(sessionId);
                }
            });
        } else {
            transcriptExportService.export(sessionId);
        }

        messagingTemplate.convertAndSend("/topic/callcenter/chat/session/" + sessionId, toView(session));
        // Sem custo se não houver bot em execução para esta sessão (ChatFlowLauncherService só
        // age se tiver um driver registrado) — destrava a thread do fluxo caso o encerramento
        // tenha vindo de fora do bot (ex.: ADMIN liberando uma conversa travada), em vez de
        // deixá-la bloqueada até o timeout do nó atual.
        eventPublisher.publishEvent(new ChatSessionEndedEvent(sessionId));
        log.info("Sessão de chat encerrada: id={} dispositionId={}", sessionId, dispositionId);
        return session;
    }

    @Transactional(readOnly = true)
    public List<CcChatSession> listWaiting(Long queueId) {
        return sessionRepository.findByQueueIdAndStatusOrderByStartedAtAsc(queueId, "waiting");
    }

    @Transactional(readOnly = true)
    public List<CcChatSession> listMine() {
        CcAgent agent = agentStateService.currentAgent();
        return sessionRepository.findByAssignedAgentIdAndStatusOrderByClaimedAtAsc(agent.getId(), "active");
    }

    @Transactional(readOnly = true)
    public List<CcChatMessage> listMessages(Long sessionId) {
        findSessionOrThrow(sessionId);
        return messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    private CcChatSession findSessionOrThrow(Long sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Sessão de chat não encontrada: " + sessionId));
    }

    /** ADMIN sempre pode encerrar (ex: liberar uma conversa travada); não-ADMIN só a própria. */
    private void assertOwnerOrAdmin(CcChatSession session) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isAdmin = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (isAdmin) {
            return;
        }
        CcAgent agent = agentStateService.currentAgent();
        if (session.getAssignedAgent() == null || !session.getAssignedAgent().getId().equals(agent.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Você não é o agente responsável por esta conversa.");
        }
    }

    private ChatSessionView toView(CcChatSession session) {
        return new ChatSessionView(
                session.getId(),
                session.getStatus(),
                session.getCustomerName(),
                session.getAssignedAgent() != null ? session.getAssignedAgent().getId() : null,
                session.getClaimedAt(),
                session.getClosedAt());
    }

    /** Payload mínimo publicado em /topic/callcenter/chat/queue/{id} — só sinaliza que a fila mudou (entrada/saída), o frontend recarrega a lista via GET. */
    public record ChatQueueEvent(Long sessionId, String customerName, LocalDateTime startedAt) {}

    /** Payload publicado em /topic/callcenter/chat/session/{id} a cada mudança de estado/mensagem. */
    public record ChatSessionView(Long id, String status, String customerName, Long assignedAgentId,
                                   LocalDateTime claimedAt, LocalDateTime closedAt) {}
}
