package com.asteriskia.domain.callcenter.chat;

import java.io.File;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * ChatAttachmentRetentionScheduler — expurgo noturno de anexos mais velhos que a retenção
 * configurada em cada {@link CcChatChannel} (Fase 7d, D6). Nunca apaga por cota estar cheia — só
 * por idade, mesma disciplina de "nunca apagar silenciosamente dentro da janela de retenção só
 * por estar cheio" decidida com o usuário. Mesmo padrão de scheduler noturno de
 * {@code CostAlertScheduler}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatAttachmentRetentionScheduler {

    private final CcChatChannelRepository channelRepository;
    private final CcChatAttachmentRepository attachmentRepository;
    private final ChatAttachmentService attachmentService;

    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void purgeExpired() {
        for (CcChatChannel channel : channelRepository.findAll()) {
            var cutoff = LocalDateTime.now().minusDays(channel.getAttachmentRetentionDays());
            List<CcChatAttachment> expired = attachmentRepository.findExpiredForChannel(channel.getId(), cutoff);
            for (CcChatAttachment attachment : expired) {
                File file = attachmentService.resolveFile(attachment);
                if (file.isFile() && !file.delete()) {
                    log.warn("Falha ao remover arquivo de anexo expirado: id={} path={}", attachment.getId(), file);
                }
                attachmentRepository.delete(attachment);
            }
            if (!expired.isEmpty()) {
                log.info("Expurgo de anexos do canal {}: {} arquivo(s) removido(s) (retenção {} dias).",
                        channel.getCode(), expired.size(), channel.getAttachmentRetentionDays());
            }
        }
    }
}
