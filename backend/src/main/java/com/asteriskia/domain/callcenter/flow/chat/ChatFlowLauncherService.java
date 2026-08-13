package com.asteriskia.domain.callcenter.flow.chat;

import com.asteriskia.domain.callcenter.chat.CcChatService;
import com.asteriskia.domain.callcenter.chat.ChatBotSessionStartedEvent;
import com.asteriskia.domain.callcenter.chat.ChatCustomerMessageReceivedEvent;
import com.asteriskia.domain.callcenter.chat.ChatSessionEndedEvent;
import com.asteriskia.domain.callcenter.flow.engine.FlowExecutionEngine;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * ChatFlowLauncherService — ponte entre o domínio de chat e o motor de fluxo (Fase 24). Ouve
 * {@link ChatBotSessionStartedEvent} (instancia o {@link ChatChannelDriver} e dispara a execução
 * num pool de threads limitado — sem isso, uma thread daemon nova por sessão, sem cap nenhum,
 * era um vetor real de esgotamento de threads: qualquer usuário da rede corporativa podia iniciar
 * muitas sessões de chat com bot em paralelo, cada uma prendendo uma thread bloqueada até 60s no
 * nó "coletar_texto"),
 * {@link ChatCustomerMessageReceivedEvent} (repassa ao driver registrado da sessão, se houver —
 * silenciosamente ignorado quando não há bot em execução) e {@link ChatSessionEndedEvent}
 * (encerramento por outra via — ex.: ADMIN força o fechamento de uma sessão "bot" travada —
 * destrava a thread do fluxo em vez de deixá-la rodar até o timeout do nó). Eventos em vez de
 * chamada direta: evita dependência circular entre o pacote {@code chat} (publica os eventos) e
 * este pacote {@code flow.chat} (cujo driver já depende de {@code CcChatService}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatFlowLauncherService {

    /** Escala da VPS de dev/homologação (poucos chats simultâneos) — revisitar quando o volume
     * real for dimensionado para servidor dedicado, mesmo padrão de nota já usado em outras
     * partes do projeto. Acima do limite, novas sessões de bot ficam enfileiradas (nunca
     * rejeitadas) até uma thread liberar. */
    private static final int MAX_CONCURRENT_BOT_SESSIONS = 30;

    private final CcChatService chatService;
    private final FlowExecutionEngine flowExecutionEngine;
    private final Map<Long, ChatChannelDriver> driversBySessionId = new ConcurrentHashMap<>();
    private final ExecutorService flowExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_BOT_SESSIONS, runnable -> {
        var thread = new Thread(runnable, "callcenter-chat-flow");
        thread.setDaemon(true);
        return thread;
    });

    /** {@code AFTER_COMMIT} (com {@code fallbackExecution=true} para o caso raro de nenhuma
     * transação estar ativa no momento da publicação, ex.: chamada direta em teste) — sem isso, a
     * thread do fluxo era disparada ainda dentro da transação de {@code CcChatService#startSession}
     * (não commitada) e podia tentar ler a sessão recém-criada antes do INSERT ser visível
     * (READ_COMMITTED), falhando de forma intermitente na primeira mensagem do bot. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onBotSessionStarted(ChatBotSessionStartedEvent event) {
        var driver = new ChatChannelDriver(chatService, event.sessionId());
        driversBySessionId.put(event.sessionId(), driver);
        var channelId = "chat-session-" + event.sessionId();
        flowExecutor.submit(() -> {
            try {
                flowExecutionEngine.startForFlow(event.flowId(), channelId, channelId, driver);
            } finally {
                driversBySessionId.remove(event.sessionId());
            }
        });
    }

    @PreDestroy
    void shutdown() {
        flowExecutor.shutdown();
        try {
            if (!flowExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                flowExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            flowExecutor.shutdownNow();
        }
    }

    @EventListener
    public void onCustomerMessage(ChatCustomerMessageReceivedEvent event) {
        var driver = driversBySessionId.get(event.sessionId());
        if (driver != null) {
            driver.onCustomerMessage(event.text());
        }
    }

    @EventListener
    public void onSessionEnded(ChatSessionEndedEvent event) {
        var driver = driversBySessionId.get(event.sessionId());
        if (driver != null) {
            driver.onSessionEnded();
        }
    }
}
