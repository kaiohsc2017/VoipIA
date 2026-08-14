package com.asteriskia.domain.callcenter.chat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatAttachmentRetentionSchedulerTest {

    @Mock private CcChatChannelRepository channelRepository;
    @Mock private CcChatAttachmentRepository attachmentRepository;
    @Mock private ChatAttachmentService attachmentService;

    private ChatAttachmentRetentionScheduler scheduler;
    private File tempFile;

    @BeforeEach
    void setUp() throws IOException {
        scheduler = new ChatAttachmentRetentionScheduler(channelRepository, attachmentRepository, attachmentService);
        tempFile = Files.createTempFile("anexo-expirado", ".csv").toFile();
    }

    @AfterEach
    void tearDown() {
        tempFile.delete();
    }

    private CcChatChannel channelWithRetention(long id, int retentionDays) {
        CcChatChannel channel = new CcChatChannel();
        channel.setId(id);
        channel.setCode("webchat");
        channel.setAttachmentRetentionDays(retentionDays);
        return channel;
    }

    @Test
    void purgeExpired_removesFileAndRow() {
        var channel = channelWithRetention(1L, 10);
        var attachment = CcChatAttachment.builder().id(9L).sessionId(5L).build();
        when(channelRepository.findAll()).thenReturn(List.of(channel));
        when(attachmentRepository.findExpiredForChannel(org.mockito.ArgumentMatchers.eq(1L), any())).thenReturn(List.of(attachment));
        when(attachmentService.resolveFile(attachment)).thenReturn(tempFile);

        scheduler.purgeExpired();

        verify(attachmentRepository).delete(attachment);
        org.assertj.core.api.Assertions.assertThat(tempFile).doesNotExist();
    }

    @Test
    void purgeExpired_noExpired_neverDeletes() {
        var channel = channelWithRetention(1L, 10);
        when(channelRepository.findAll()).thenReturn(List.of(channel));
        when(attachmentRepository.findExpiredForChannel(any(), any())).thenReturn(List.of());

        scheduler.purgeExpired();

        verify(attachmentRepository, never()).delete(any());
    }
}
