package com.asteriskia.domain.callcenter.chat;

import com.asteriskia.config.ResourceNotFoundException;
import com.asteriskia.domain.callcenter.CcQueueRepository;
import com.asteriskia.domain.callcenter.flow.CcFlowRepository;
import java.util.List;
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
        channel.setType(request.type() == null || request.type().isBlank() ? "webchat" : request.type().trim());
        channel.setGreetingMessage(request.greetingMessage());
        channel.setAwayMessage(request.awayMessage());
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
