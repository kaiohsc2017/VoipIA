package com.asteriskia.domain.callcenter.chat;

import com.asteriskia.config.ResourceNotFoundException;
import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.CcQueue;
import com.asteriskia.domain.callcenter.CcQueueRepository;
import com.asteriskia.domain.callcenter.interaction.AgentState;
import com.asteriskia.domain.callcenter.interaction.CallCenterAgentStateService;
import com.asteriskia.domain.callcenter.interaction.CcDisposition;
import com.asteriskia.domain.callcenter.interaction.CcDispositionRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    @Transactional
    public CcChatSession startSession(String channelCode, Long queueId, String customerRef, String customerName) {
        CcChatChannel channel = channelRepository.findByCodeAndActiveTrue(channelCode)
                .orElseThrow(() -> new ResourceNotFoundException("Canal de chat inválido ou inativo: " + channelCode));
        CcQueue queue = queueRepository.findById(queueId)
                .orElseThrow(() -> new ResourceNotFoundException("Fila não encontrada: " + queueId));
        if (customerRef == null || customerRef.isBlank()) {
            throw new IllegalArgumentException("customerRef é obrigatório.");
        }

        CcChatSession session = sessionRepository.save(CcChatSession.builder()
                .channel(channel)
                .queue(queue)
                .businessUnit(queue.getBusinessUnit())
                .customerRef(customerRef)
                .customerName(customerName)
                .status("waiting")
                .startedAt(LocalDateTime.now())
                .build());

        messagingTemplate.convertAndSend("/topic/callcenter/chat/queue/" + queueId,
                new ChatQueueEvent(session.getId(), session.getCustomerName(), session.getStartedAt()));
        log.info("Sessão de chat iniciada: id={} canal={} fila={}", session.getId(), channelCode, queueId);
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
                if (!"waiting".equals(session.getStatus()) && !"active".equals(session.getStatus())) {
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
        return message;
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

        messagingTemplate.convertAndSend("/topic/callcenter/chat/session/" + sessionId, toView(session));
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
