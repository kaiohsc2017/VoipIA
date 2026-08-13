package com.asteriskia.domain.callcenter.interaction;

import com.asteriskia.domain.callcenter.CcAgent;
import com.asteriskia.domain.callcenter.CcExtension;
import com.asteriskia.domain.callcenter.CcExtensionRepository;
import com.asteriskia.domain.callcenter.CcQueueRepository;
import com.asteriskia.integration.ami.AmiSession;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * CallCenterAmiEventListener — conexão AMI persistente e event-driven (Fase 4), diferente do
 * padrão request/response de {@link com.asteriskia.integration.ami.AmiOriginateService}. Alimenta
 * {@code cc_interactions}/{@code cc_agent_states} a partir dos eventos reais de fila/agente do
 * Asterisk (QueueCallerJoin, AgentConnect, AgentComplete, QueueCallerAbandon).
 *
 * <p><b>Não validado contra tráfego real de fila nesta entrega</b> — sem chamada de teste
 * passando por uma fila configurada, não há como confirmar os nomes exatos de campo que este
 * Asterisk emite (podem variar por versão/config). O parsing (@link AmiEventParser}) e a lógica de
 * cada handler têm teste unitário; a conexão/reconexão foi validada (log de conexão bem-sucedida
 * ou de retry), mas o mapeamento evento→estado só pode ser confirmado com uma chamada real
 * atravessando uma fila com agente logado.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallCenterAmiEventListener {

    @Value("${app.asterisk.ami.host}")
    private String host;

    @Value("${app.asterisk.ami.port:5038}")
    private int port;

    @Value("${app.asterisk.ami.user}")
    private String user;

    @Value("${app.asterisk.ami.password}")
    private String password;

    @Value("${app.callcenter.ami-listener.enabled:true}")
    private boolean enabled;

    private static final int RECONNECT_DELAY_MS = 5000;

    private final CcQueueRepository queueRepository;
    private final CcExtensionRepository extensionRepository;
    private final CcInteractionRepository interactionRepository;
    private final CcInteractionEventRepository interactionEventRepository;
    private final CallCenterAgentStateService agentStateService;

    private volatile boolean running = false;
    private volatile AmiSession activeSession;

    @PostConstruct
    void start() {
        if (!enabled) {
            log.info(
                    "Listener de eventos AMI do Call Center desabilitado "
                            + "(app.callcenter.ami-listener.enabled=false).");
            return;
        }
        running = true;
        Thread thread = new Thread(this::runLoop, "callcenter-ami-listener");
        thread.setDaemon(true);
        thread.start();
    }

    @PreDestroy
    void stop() {
        running = false;
        var session = activeSession;
        if (session != null) {
            try {
                session.close();
            } catch (IOException ignored) {
                // Encerramento do processo — não há mais nada útil a fazer com o erro.
            }
        }
    }

    private void runLoop() {
        while (running) {
            try {
                connectAndConsume();
            } catch (IOException e) {
                if (running) {
                    log.warn(
                            "Listener AMI do Call Center: conexão perdida ({}), reconectando em {}ms.",
                            e.getMessage(),
                            RECONNECT_DELAY_MS);
                }
            }
            if (running) {
                sleepQuietly(RECONNECT_DELAY_MS);
            }
        }
    }

    private void connectAndConsume() throws IOException {
        // timeout 0 = SO_TIMEOUT infinito — precisamos bloquear indefinidamente esperando o
        // próximo evento, diferente do uso request/response de AmiOriginateService.
        try (var ami = AmiSession.connect(host, port, 0)) {
            activeSession = ami;
            ami.send(
                    Map.of(
                            "Action", "Login",
                            "Username", user,
                            "Secret", password,
                            "Events", "on"));
            var loginResponse = ami.readBlock();
            if (!loginResponse.contains("Success")) {
                log.error("Listener AMI do Call Center: falha na autenticação com {}:{}.", host, port);
                return;
            }
            log.info("Listener AMI do Call Center conectado a {}:{}.", host, port);
            while (running) {
                var block = ami.readBlock();
                if (block.isBlank()) {
                    continue;
                }
                handleEvent(AmiEventParser.parse(block));
            }
        } finally {
            activeSession = null;
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void handleEvent(Map<String, String> event) {
        var type = event.get("Event");
        if (type == null) {
            return;
        }
        try {
            switch (type) {
                case "QueueCallerJoin" -> onQueueCallerJoin(event);
                case "AgentConnect" -> onAgentConnect(event);
                case "AgentComplete" -> onAgentComplete(event);
                case "QueueCallerAbandon" -> onQueueCallerAbandon(event);
                default -> {
                    // Demais eventos AMI (heartbeat, status de peer, etc.) — fora do escopo desta fase.
                }
            }
        } catch (Exception e) {
            log.warn("Listener AMI do Call Center: falha ao processar evento {}: {}", type, e.getMessage());
        }
    }

    @Transactional
    void onQueueCallerJoin(Map<String, String> event) {
        var uniqueId = event.get("Uniqueid");
        if (uniqueId == null || interactionRepository.existsByChannelUniqueId(uniqueId)) {
            return;
        }
        var queue = queueRepository.findByName(event.get("Queue")).orElse(null);
        var interaction =
                interactionRepository.save(
                        CcInteraction.builder()
                                .queue(queue)
                                .direction(Direction.INBOUND)
                                .channelUniqueId(uniqueId)
                                .ani(event.get("CallerIDNum"))
                                .businessUnit(queue == null ? null : queue.getBusinessUnit())
                                .queuedAt(LocalDateTime.now())
                                .build());
        recordEvent(interaction, "QueueCallerJoin", event);
    }

    @Transactional
    void onAgentConnect(Map<String, String> event) {
        var uniqueId = event.get("Uniqueid");
        if (uniqueId == null) {
            return;
        }
        interactionRepository
                .findByChannelUniqueId(uniqueId)
                .ifPresent(
                        interaction -> {
                            var agent = resolveAgentFromInterface(event.get("Member"));
                            interaction.setAnsweredAt(LocalDateTime.now());
                            interaction.setAgent(agent);
                            interactionRepository.save(interaction);
                            recordEvent(interaction, "AgentConnect", event);
                            if (agent != null) {
                                agentStateService.setState(agent, AgentState.EM_ATENDIMENTO, null);
                            }
                        });
    }

    @Transactional
    void onAgentComplete(Map<String, String> event) {
        var uniqueId = event.get("Uniqueid");
        if (uniqueId == null) {
            return;
        }
        interactionRepository
                .findByChannelUniqueId(uniqueId)
                .ifPresent(
                        interaction -> {
                            interaction.setEndedAt(LocalDateTime.now());
                            interactionRepository.save(interaction);
                            recordEvent(interaction, "AgentComplete", event);
                            // ACW (After Call Work) começa aqui — o agente volta a DISPONIVEL só ao
                            // tabular (CallCenterInteractionService.applyDisposition).
                            if (interaction.getAgent() != null) {
                                agentStateService.setState(interaction.getAgent(), AgentState.ACW, null);
                            }
                        });
    }

    @Transactional
    void onQueueCallerAbandon(Map<String, String> event) {
        var uniqueId = event.get("Uniqueid");
        if (uniqueId == null) {
            return;
        }
        interactionRepository
                .findByChannelUniqueId(uniqueId)
                .ifPresent(
                        interaction -> {
                            interaction.setEndedAt(LocalDateTime.now());
                            interactionRepository.save(interaction);
                            recordEvent(interaction, "QueueCallerAbandon", event);
                        });
    }

    private CcAgent resolveAgentFromInterface(String interfaceName) {
        if (interfaceName == null) {
            return null;
        }
        var extension =
                interfaceName.contains("/")
                        ? interfaceName.substring(interfaceName.indexOf('/') + 1)
                        : interfaceName;
        return extensionRepository.findByExtension(extension).map(CcExtension::getAgent).orElse(null);
    }

    private void recordEvent(CcInteraction interaction, String type, Map<String, String> raw) {
        interactionEventRepository.save(
                CcInteractionEvent.builder()
                        .interaction(interaction)
                        .eventType(type)
                        .details(raw.toString())
                        .build());
    }
}
