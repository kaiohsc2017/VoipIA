package com.asteriskia.domain.callcenter.flow.chat;

import com.asteriskia.domain.callcenter.chat.CcChatService;
import com.asteriskia.domain.callcenter.flow.engine.ChannelDriver;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * ChatChannelDriver — implementação de {@link ChannelDriver} para o canal chat (Fase 24),
 * primeira prova real da premissa "motor de fluxo agnóstico de canal" (Fase 5). Mesmo padrão de
 * {@code AriVoiceChannelDriver}: instanciado uma vez por sessão (por
 * {@code ChatFlowLauncherService}), guarda estado próprio (fila de mensagens do cliente, flag de
 * encerramento) — não é bean Spring singleton.
 */
@Slf4j
public class ChatChannelDriver implements ChannelDriver {

    private final CcChatService chatService;
    private final Long sessionId;
    private final BlockingQueue<String> customerMessages = new LinkedBlockingQueue<>();
    private final Map<String, String> variables = new ConcurrentHashMap<>();
    private volatile boolean ended = false;

    public ChatChannelDriver(CcChatService chatService, Long sessionId) {
        this.chatService = chatService;
        this.sessionId = sessionId;
    }

    /** Chamado por {@code ChatFlowLauncherService} ao ouvir {@code ChatCustomerMessageReceivedEvent}
     * desta sessão. */
    public void onCustomerMessage(String text) {
        customerMessages.offer(text);
    }

    /** Chamado por {@code ChatFlowLauncherService} quando a sessão é encerrada por outra via
     * (ex.: um agente assume/encerra manualmente enquanto o bot ainda esperava resposta). */
    public void onSessionEnded() {
        ended = true;
    }

    @Override
    public void playMessage(String audioPath, String text) {
        if (text == null || text.isBlank()) {
            if (audioPath != null && !audioPath.isBlank()) {
                log.info("Nó tocar_audio/menu_opcoes com só áudio (sem texto) em canal chat — nada pra enviar, ignorando (sessão {}).", sessionId);
            }
            return;
        }
        chatService.postBotMessage(sessionId, text);
    }

    @Override
    public PromptResult promptChoice(List<String> validChoices, Duration timeout) {
        var deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (ended) {
                return PromptResult.hungUp();
            }
            String text;
            try {
                text = customerMessages.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return PromptResult.hungUp();
            }
            if (text != null) {
                var trimmed = text.trim();
                return validChoices.contains(trimmed) ? PromptResult.chosen(trimmed) : PromptResult.invalid(trimmed);
            }
        }
        return PromptResult.timeout();
    }

    @Override
    public TextResult collectText(Duration timeout) {
        var deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (ended) {
                return TextResult.hungUp();
            }
            String text;
            try {
                text = customerMessages.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return TextResult.hungUp();
            }
            if (text != null) {
                return TextResult.collected(text);
            }
        }
        return TextResult.timeout();
    }

    @Override
    public RecordResult recordResponse(Duration maxDuration) {
        // Gravação de resposta falada (pesquisa de satisfação, Fase 21) não se aplica a chat —
        // nenhum fluxo de canal chat referencia esse nó (pesquisa_satisfacao é channel="both" no
        // catálogo, mas o modo FALADA_IA/DTMF_COMENTARIO grava áudio, sem sentido em texto).
        throw new UnsupportedOperationException("recordResponse não se aplica ao canal chat.");
    }

    @Override
    public void setVariable(String name, String value) {
        variables.put(name, value);
    }

    @Override
    public String getVariable(String name) {
        return variables.get(name);
    }

    @Override
    public void transferToQueue(String queueExtension) {
        ended = true;
        chatService.transferToHumanQueue(sessionId, queueExtension);
    }

    @Override
    public void transferToExtension(String extension) {
        // "transferir_ramal" é exclusivo do canal voz no catálogo (FlowGraphNodeCatalog) — chat
        // não tem conceito de ramal SIP para transferir. FlowGraphValidator já bloqueia a
        // publicação de um fluxo de chat com esse nó (checagem de "channel"), então nenhum fluxo
        // publicado deveria chegar aqui em produção — mesmo padrão de
        // AriVoiceChannelDriver.collectText (nó exclusivo do outro canal). A exceção é capturada
        // pelo FlowExecutionEngine, que tenta um caminho de fuga para "enviar_fila" antes de
        // encerrar a sessão, nunca deixando o chat travado.
        throw new UnsupportedOperationException("transferToExtension não se aplica ao canal chat.");
    }

    @Override
    public void end() {
        ended = true;
        chatService.closeByBot(sessionId);
    }
}
