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
 * <p><b>Validado parcialmente com tráfego real em 2026-08-15</b> (originate real via
 * {@code channel originate Local/<fila>@ramais-internos}, sem SIPp — decisão do usuário de não
 * usar SIPp continua valendo, este teste não gera carga, só confirma o caminho funcional):
 * {@code QueueCallerJoin}/{@code QueueCallerAbandon} confirmados criando/fechando
 * {@code cc_interactions} de verdade, com os nomes de campo reais deste Asterisk. {@code
 * AgentConnect} continua não confirmado — nenhum ramal de agente estava registrado (sem
 * telefone/softphone real) nesta VPS no momento do teste; falta repetir o teste com um agente
 * realmente atendendo. Esta rodada também encontrou e corrigiu 4 bugs reais que só um teste com
 * tráfego real revelaria: (1) {@code queue-recording-config} sendo chamado via POST pelo dialplan
 * quando o endpoint só aceitava GET; (2) delimitador {@code ;} em {@code CUT()} sendo cortado como
 * comentário pelo parser do {@code extensions.conf}, deixando a opção de não gravar por fila
 * sempre inoperante; (3) esta própria conexão AMI travando para sempre (sem log de erro) quando o
 * Asterisk é reiniciado abruptamente, por usar SO_TIMEOUT=0 — corrigido com
 * {@link #AMI_READ_TIMEOUT_MS}; (4) o mais grave — um restart **gracioso** do Asterisk
 * ({@code docker compose restart}, SIGTERM) fecha o socket limpo (EOF), e
 * {@link com.asteriskia.integration.ami.AmiSession#readBlock()} devolvia silenciosamente string
 * vazia em vez de lançar exceção, fazendo este listener entrar num laço apertado sem espera nenhuma
 * — 100% de CPU, para sempre, sem nunca reconectar (medido ao vivo: 98,5% de CPU por mais de 2
 * minutos). Corrigido lançando {@code EOFException} em {@code AmiSession}. Os 4 bugs foram
 * revalidados com testes reais de restart do Asterisk (gracioso e abrupto), não só automatizados.
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

    /** Achado real de 2026-08-15 (primeira validação com tráfego real de fila): a conexão AMI
     * usava SO_TIMEOUT=0 (bloqueio infinito) — quando o Asterisk é recriado/reiniciado, o socket
     * TCP antigo fica preso num read() que nunca retorna (sem FIN/RST perceptível pelo lado do
     * backend), e o listener nunca reconecta sozinho, silenciosamente, sem log de erro nenhum.
     * Timeout finito faz o read() estourar {@link java.net.SocketTimeoutException} (subtipo de
     * IOException) periodicamente, reaproveitando o mesmo caminho de reconexão do runLoop — pior
     * caso, reconecta a cada intervalo mesmo com fila ociosa (login novo é barato). */
    private static final int AMI_READ_TIMEOUT_MS = 60_000;

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
        try (var ami = AmiSession.connect(host, port, AMI_READ_TIMEOUT_MS)) {
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
                case "QueueCallerLeave" -> onQueueCallerLeave(event);
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
                                .positionOnJoin(parsePositionOrNull(event.get("Position")))
                                .channelName(event.get("Channel"))
                                .build());
        recordEvent(interaction, "QueueCallerJoin", event);
    }

    private Integer parsePositionOrNull(String raw) {
        try {
            return raw == null ? null : Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Chamador saiu da fila sem ser atendido nem marcado como {@code Abandon} (ex: transbordo
     * para outra fila via {@code QueueTransfer}, ou {@code Redirect} manual do supervisor —
     * Fase 15.3). Sem tratar este evento, a interação ficava marcada como "esperando" pra sempre,
     * porque só {@code QueueCallerAbandon} fechava {@code endedAt}. */
    @Transactional
    void onQueueCallerLeave(Map<String, String> event) {
        var uniqueId = event.get("Uniqueid");
        if (uniqueId == null) {
            return;
        }
        interactionRepository
                .findByChannelUniqueId(uniqueId)
                .filter(interaction -> interaction.getAnsweredAt() == null && interaction.getEndedAt() == null)
                .ifPresent(
                        interaction -> {
                            interaction.setEndedAt(LocalDateTime.now());
                            interactionRepository.save(interaction);
                            recordEvent(interaction, "QueueCallerLeave", event);
                        });
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
