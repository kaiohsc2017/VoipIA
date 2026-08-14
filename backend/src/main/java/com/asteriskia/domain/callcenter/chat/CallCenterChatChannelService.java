package com.asteriskia.domain.callcenter.chat;

import com.asteriskia.config.ResourceNotFoundException;
import com.asteriskia.domain.callcenter.CcQueueRepository;
import com.asteriskia.domain.callcenter.flow.CcFlowRepository;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CallCenterChatChannelService — CRUD de {@link CcChatChannel} (Fase 24). RBAC via
 * {@code PERM_READ_callcenter.chat}/{@code PERM_WRITE_callcenter.chat} — mesmo resource já usado
 * pelo restante do canal de chat (matcher genérico {@code /api/v1/callcenter/chat/**}), sem
 * resource novo.
 */
@Service
@RequiredArgsConstructor
public class CallCenterChatChannelService {

    /** Fase 7e — achado CRITICAL de segurança: sem esta allowlist, qualquer usuário com só
     * {@code PERM_WRITE_callcenter.chat} (bem menos privilegiado que {@code telecom.settings})
     * podia apontar {@code telegramBotTokenRef} para QUALQUER chave do .env (ex.:
     * {@code POSTGRES_PASSWORD}, {@code BACKEND_JWT_SECRET}, {@code INTERNAL_API_KEY}) —
     * {@code TelegramLongPollingClient} resolveria e vazaria esse segredo pra um servidor externo
     * (Telegram) a cada ciclo de polling. O padrão trava a referência a variantes de um único
     * nome de base, nunca uma chave arbitrária do arquivo. */
    private static final Pattern TELEGRAM_TOKEN_REF_PATTERN =
            Pattern.compile("^CALLCENTER_TELEGRAM_BOT_TOKEN(_[A-Z0-9_]+)?$");

    private final CcChatChannelRepository channelRepository;
    private final CcQueueRepository queueRepository;
    private final CcFlowRepository flowRepository;

    @Transactional(readOnly = true)
    public List<CcChatChannel> findAll() {
        return channelRepository.findAll();
    }

    @Transactional(readOnly = true)
    public CcChatChannel findById(Long id) {
        return channelRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Canal de chat não encontrado: " + id));
    }

    @Transactional
    public CcChatChannel create(ChatChannelRequest request) {
        channelRepository.findByCodeAndActiveTrue(request.code().trim()).ifPresent(c -> {
            throw new IllegalArgumentException("Já existe um canal ativo com o código \"" + request.code() + "\".");
        });
        var channel = CcChatChannel.builder()
                .code(request.code().trim())
                .displayName(request.displayName().trim())
                .active(request.active() == null || request.active())
                .build();
        applyRequest(channel, request);
        return channelRepository.save(channel);
    }

    @Transactional
    public CcChatChannel update(Long id, ChatChannelRequest request) {
        var channel = findById(id);
        var newCode = request.code().trim();
        if (!newCode.equalsIgnoreCase(channel.getCode())) {
            // Código é UNIQUE no banco (V56) independente de active — sem esta checagem, uma
            // colisão vira DataIntegrityViolationException sem handler dedicado (500 genérico) em
            // vez do 400 claro que create() já dá para o mesmo cenário.
            channelRepository.findByCodeAndActiveTrue(newCode).ifPresent(c -> {
                throw new IllegalArgumentException("Já existe um canal ativo com o código \"" + request.code() + "\".");
            });
        }
        channel.setCode(newCode);
        channel.setDisplayName(request.displayName().trim());
        channel.setActive(request.active() == null || request.active());
        applyRequest(channel, request);
        return channelRepository.save(channel);
    }

    private void applyRequest(CcChatChannel channel, ChatChannelRequest request) {
        String type = request.type() == null || request.type().isBlank() ? "webchat" : request.type().trim();
        channel.setType(type);
        channel.setGreetingMessage(request.greetingMessage());
        channel.setAwayMessage(request.awayMessage());
        // Fase 7e — token do bot Telegram é só uma REFERÊNCIA (chave no .env, nunca o valor em
        // texto puro nesta tabela); obrigatória quando o canal é do tipo telegram, senão o
        // TelegramLongPollingClient nunca teria como resolver o token real.
        if ("telegram".equals(type)) {
            String ref = request.telegramBotTokenRef() == null ? "" : request.telegramBotTokenRef().trim();
            if (!TELEGRAM_TOKEN_REF_PATTERN.matcher(ref).matches()) {
                throw new IllegalArgumentException(
                        "telegramBotTokenRef inválido — deve seguir o padrão CALLCENTER_TELEGRAM_BOT_TOKEN[_sufixo], nunca uma chave arbitrária do .env.");
            }
        }
        channel.setTelegramBotTokenRef(
                request.telegramBotTokenRef() == null || request.telegramBotTokenRef().isBlank()
                        ? null
                        : request.telegramBotTokenRef().trim());
        validateAttachmentConfig(request.attachmentQuotaBytes(), request.attachmentRetentionDays());
        channel.setAttachmentQuotaBytes(request.attachmentQuotaBytes() != null ? request.attachmentQuotaBytes() : 2_147_483_648L);
        channel.setAttachmentRetentionDays(request.attachmentRetentionDays() != null ? request.attachmentRetentionDays() : 10);
        channel.setDefaultQueue(
                request.defaultQueueId() == null
                        ? null
                        : queueRepository.findById(request.defaultQueueId())
                                .orElseThrow(() -> new ResourceNotFoundException("Fila não encontrada: " + request.defaultQueueId())));
        channel.setBotFlow(
                request.botFlowId() == null
                        ? null
                        : flowRepository.findById(request.botFlowId())
                                .orElseThrow(() -> new ResourceNotFoundException("Fluxo não encontrado: " + request.botFlowId())));
    }

    private void validateAttachmentConfig(Long quotaBytes, Integer retentionDays) {
        if (quotaBytes != null && quotaBytes <= 0) {
            throw new IllegalArgumentException("Cota de anexos deve ser maior que zero.");
        }
        if (retentionDays != null && retentionDays <= 0) {
            throw new IllegalArgumentException("Retenção de anexos deve ser maior que zero dias.");
        }
    }
}
