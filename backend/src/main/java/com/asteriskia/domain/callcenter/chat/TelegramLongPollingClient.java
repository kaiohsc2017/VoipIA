package com.asteriskia.domain.callcenter.chat;

import com.asteriskia.domain.settings.EnvFileStore;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * TelegramLongPollingClient — loop de long polling do canal Telegram (Fase 7e, D1/D2). Nunca
 * expõe rota pública nova: o backend só chama para fora ({@code getUpdates}), a rede corporativa
 * não precisa aceitar nenhuma entrada da internet. Mesmo padrão estrutural dos schedulers já
 * existentes (ex.: {@code ChatAttachmentRetentionScheduler}, {@code CostAlertScheduler}) — erro
 * num canal nunca derruba o scheduler nem afeta os demais canais.
 *
 * <p>Também ouve {@link ChatAgentMessageSentEvent} para entregar de volta ao Telegram a resposta
 * do agente/motor de fluxo — o mesmo {@link CcChatService} usado pelo webchat já publica esse
 * evento, sem nenhuma lógica de canal duplicada ali.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramLongPollingClient {

    /** Canal público sem token nenhum configurado — evita reprocessar erro a cada ciclo com log
     * repetido; avisa uma vez, tenta de novo no próximo ciclo (não é um estado permanente, o
     * operador pode configurar o token a qualquer momento). */
    private static final String CHANNEL_TYPE = "telegram";

    /** Segunda camada de defesa (a primeira é a validação em {@code CallCenterChatChannelService}
     * na escrita) — nunca resolve uma referência fora deste padrão, mesmo que uma linha tenha
     * chegado ao banco por outra via (ex.: acesso direto ao banco). Sem isso, um
     * {@code telegramBotTokenRef} arbitrário resolveria qualquer chave do .env e a enviaria pra
     * um servidor externo (Telegram) a cada ciclo de polling — achado CRITICAL de segurança. */
    private static final Pattern TOKEN_REF_PATTERN = Pattern.compile("^CALLCENTER_TELEGRAM_BOT_TOKEN(_[A-Z0-9_]+)?$");

    private final CcChatChannelRepository channelRepository;
    private final CcChatSessionRepository sessionRepository;
    private final CcTelegramPollStateRepository pollStateRepository;
    private final CcChatService chatService;
    private final TelegramApiClient telegramApiClient;
    private final EnvFileStore envFileStore;

    @Scheduled(fixedDelayString = "${app.callcenter.telegram.poll-interval-ms:5000}")
    public void pollAllChannels() {
        for (CcChatChannel channel : channelRepository.findByTypeAndActiveTrue(CHANNEL_TYPE)) {
            try {
                pollChannel(channel);
            } catch (Exception e) {
                // Nunca deixa um canal com problema (token inválido, rede fora) derrubar o
                // scheduler inteiro nem impedir o polling dos demais canais Telegram.
                log.warn("Falha ao processar polling do canal Telegram {} (causa={}).",
                        channel.getCode(), e.getClass().getSimpleName());
            }
        }
    }

    private void pollChannel(CcChatChannel channel) {
        String token = resolveToken(channel);
        if (token == null || token.isBlank()) {
            log.warn("Canal Telegram \"{}\" sem token configurado (telegramBotTokenRef={}) — polling ignorado.",
                    channel.getCode(), channel.getTelegramBotTokenRef());
            return;
        }
        CcTelegramPollState state = getOrCreateState(channel.getId());
        long lastSeenUpdateId = state.getLastUpdateId();
        long offset = lastSeenUpdateId + 1;
        List<TelegramApiClient.TelegramUpdate> updates = telegramApiClient.getUpdates(token, offset, 0);
        if (updates.isEmpty()) {
            return;
        }
        // Achado MEDIUM de revisão: o offset é persistido update a update (não só ao fim do lote)
        // — se handleIncomingMessage falhar no meio do lote (ex.: sessão fechada por um agente
        // entre a consulta e o post, erro transitório de banco), os updates já processados com
        // sucesso não são reenviados no próximo ciclo. Só o update que falhou (e os seguintes,
        // ainda não tentados) ficam pendentes pro próximo poll.
        for (TelegramApiClient.TelegramUpdate update : updates) {
            // Defesa em profundidade contra reprocessamento — o offset incremental do Telegram já
            // deveria bastar, mas nunca confiamos só nisso (idempotência por update_id).
            if (update.updateId() <= state.getLastUpdateId()) {
                continue;
            }
            if (update.chatId() != null && update.text() != null) {
                handleIncomingMessage(channel, update.chatId(), update.fromName(), update.text());
            }
            // mensagem sem texto (foto/sticker/...) — fora de escopo desta fatia, mas o offset
            // ainda avança pra nunca reprocessar o mesmo update em loop.
            persistOffset(state, update.updateId());
        }
    }

    private void handleIncomingMessage(CcChatChannel channel, String chatId, String fromName, String text) {
        Optional<CcChatSession> existing =
                sessionRepository.findByChannelIdAndExternalRefAndClosedAtIsNull(channel.getId(), chatId);
        Long sessionId;
        if (existing.isPresent()) {
            sessionId = existing.get().getId();
        } else {
            CcChatSession session = chatService.startExternalSession(channel.getCode(), chatId, fromName);
            sessionId = session.getId();
        }
        chatService.postMessage(sessionId, "customer", fromName, text);
    }

    /** {@code AFTER_COMMIT} (com {@code fallbackExecution=true} para chamada direta fora de
     * transação, ex.: teste) — mesmo padrão já usado por
     * {@code ChatFlowLauncherService.onBotSessionStarted}. Sem isso, a chamada HTTP bloqueante ao
     * Telegram (até 15s) prendia uma conexão do pool aberta pela transação de
     * {@code CcChatService#postMessage}/{@code postBotMessage} fazendo puro I/O de rede — mesma
     * classe de achado HIGH já corrigida antes neste projeto (Fase 21 NPS) — e, pior, podia
     * entregar a mensagem ao cliente mesmo que a transação viesse a dar rollback depois. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAgentMessageSent(ChatAgentMessageSentEvent event) {
        sessionRepository.findById(event.sessionId()).ifPresent(session -> {
            CcChatChannel channel = session.getChannel();
            if (!CHANNEL_TYPE.equals(channel.getType()) || session.getExternalRef() == null) {
                return; // não é uma sessão Telegram — nada a entregar (webchat usa só polling do frontend)
            }
            String token = resolveToken(channel);
            if (token == null || token.isBlank()) {
                log.warn("Não foi possível entregar mensagem ao Telegram: canal \"{}\" sem token configurado.",
                        channel.getCode());
                return;
            }
            telegramApiClient.sendMessage(token, session.getExternalRef(), event.body());
        });
    }

    /** Resolve o valor real do token a partir da referência ({@code telegramBotTokenRef}) —
     * NUNCA loga o valor, só a chave de referência. Leitura direta do .env (mesmo mecanismo de
     * {@code AiProviderService}/segredos do projeto), não do banco. */
    private String resolveToken(CcChatChannel channel) {
        String ref = channel.getTelegramBotTokenRef();
        if (ref == null || ref.isBlank()) {
            return null;
        }
        if (!TOKEN_REF_PATTERN.matcher(ref).matches()) {
            log.warn("Referência de token do canal Telegram \"{}\" fora do padrão esperado — ignorada por segurança (nunca resolve chave arbitrária do .env).",
                    channel.getCode());
            return null;
        }
        try {
            return envFileStore.readRaw().get(ref);
        } catch (IOException e) {
            log.warn("Falha ao ler configuração para resolver token do canal Telegram \"{}\" (causa={}).",
                    channel.getCode(), e.getClass().getSimpleName());
            return null;
        }
    }

    // Sem @Transactional aqui — autoinvocação de dentro de pollChannel não passaria pelo proxy do
    // Spring de qualquer forma (mesma observação já registrada em
    // CallCenterNpsTranscriptionScheduler). Cada repositório já é transacional por conta própria.
    private CcTelegramPollState getOrCreateState(Long channelId) {
        return pollStateRepository.findById(channelId)
                .orElseGet(() -> pollStateRepository.save(
                        CcTelegramPollState.builder().channelId(channelId).lastUpdateId(0L).build()));
    }

    private void persistOffset(CcTelegramPollState state, long lastUpdateId) {
        state.setLastUpdateId(lastUpdateId);
        state.setUpdatedAt(LocalDateTime.now());
        pollStateRepository.save(state);
    }
}
