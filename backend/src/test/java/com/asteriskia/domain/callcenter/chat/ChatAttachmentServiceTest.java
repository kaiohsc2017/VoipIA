package com.asteriskia.domain.callcenter.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Defesa central da Fase 7d: allowlist de extensão, magic-bytes, e cota por uploader (D5/D6). */
@ExtendWith(MockitoExtension.class)
class ChatAttachmentServiceTest {

    @Mock private CcChatService chatService;
    @Mock private CcChatAttachmentRepository attachmentRepository;
    @Mock private CcChatAttachmentExtensionRepository extensionRepository;
    @Mock private SimpMessagingTemplate messagingTemplate;

    private ChatAttachmentService service;
    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        service = new ChatAttachmentService(chatService, attachmentRepository, extensionRepository, messagingTemplate);
        tempDir = Files.createTempDirectory("chat-attachments-test");
        setBasePath(service, tempDir.toString());
        lenient().when(attachmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.walk(tempDir).sorted((a, b) -> b.compareTo(a)).forEach(p -> p.toFile().delete());
    }

    private static void setBasePath(ChatAttachmentService service, String path) {
        try {
            Field field = ChatAttachmentService.class.getDeclaredField("basePath");
            field.setAccessible(true);
            field.set(service, path);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private CcChatSession sessionWith(long quotaBytes, int retentionDays) {
        CcChatChannel channel = new CcChatChannel();
        channel.setAttachmentQuotaBytes(quotaBytes);
        channel.setAttachmentRetentionDays(retentionDays);
        return CcChatSession.builder().id(5L).channel(channel).customerRef("cliente-1").build();
    }

    private CcChatService.SenderContext senderContext(CcChatSession session, String uploaderKey) {
        return new CcChatService.SenderContext(session, "Fulano", uploaderKey);
    }

    @Test
    @DisplayName("upload rejeita extensão não cadastrada")
    void upload_extensionNotAllowed_throws() {
        when(chatService.validateSender(5L, "agent", null)).thenReturn(senderContext(sessionWith(1_000_000, 10), "agente1"));
        when(extensionRepository.findByExtensionIgnoreCaseAndActiveTrue("exe")).thenReturn(Optional.empty());
        var file = new MockMultipartFile("file", "virus.exe", "application/octet-stream", new byte[] {1, 2, 3});

        assertThatThrownBy(() -> service.upload(5L, "agent", file))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("não é aceita");
    }

    @Test
    @DisplayName("upload rejeita conteúdo cujo magic-bytes não bate com a extensão")
    void upload_magicBytesMismatch_throws() {
        when(chatService.validateSender(5L, "agent", null)).thenReturn(senderContext(sessionWith(1_000_000, 10), "agente1"));
        when(extensionRepository.findByExtensionIgnoreCaseAndActiveTrue("png"))
                .thenReturn(Optional.of(CcChatAttachmentExtension.builder().extension("png").active(true).build()));
        // conteúdo de texto puro, não PNG real
        var file = new MockMultipartFile("file", "foto.png", "image/png", "não é um png de verdade".getBytes());

        assertThatThrownBy(() -> service.upload(5L, "agent", file))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("não corresponde");
    }

    @Test
    @DisplayName("upload aceita extensão sem assinatura conhecida no mapa (gap documentado)")
    void upload_unknownExtensionSignature_skipsMagicBytesCheck() {
        when(chatService.validateSender(5L, "agent", null)).thenReturn(senderContext(sessionWith(1_000_000, 10), "agente1"));
        when(extensionRepository.findByExtensionIgnoreCaseAndActiveTrue("csv"))
                .thenReturn(Optional.of(CcChatAttachmentExtension.builder().extension("csv").mimetype("text/csv").active(true).build()));
        var file = new MockMultipartFile("file", "dados.csv", "text/csv", "a,b,c\n1,2,3".getBytes());

        var attachment = service.upload(5L, "agent", file);

        assertThat(attachment.getOriginalFileName()).isEqualTo("dados.csv");
        assertThat(attachment.getContentType()).isEqualTo("text/csv");
    }

    @Test
    @DisplayName("upload rejeita quando excede a cota do uploader")
    void upload_overQuota_throws() {
        when(chatService.validateSender(5L, "agent", null)).thenReturn(senderContext(sessionWith(100, 10), "agente1"));
        when(extensionRepository.findByExtensionIgnoreCaseAndActiveTrue("csv"))
                .thenReturn(Optional.of(CcChatAttachmentExtension.builder().extension("csv").active(true).build()));
        when(attachmentRepository.sumSizeByUploaderSince(anyString(), any(LocalDateTime.class))).thenReturn(95L);
        var file = new MockMultipartFile("file", "dados.csv", "text/csv", "0123456789".getBytes()); // 10 bytes, 95+10 > 100

        assertThatThrownBy(() -> service.upload(5L, "agent", file))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Cota de anexos");
    }

    @Test
    @DisplayName("upload bem-sucedido grava o arquivo dentro do diretório do uploader e persiste o registro")
    void upload_success_savesFileAndRecord() {
        when(chatService.validateSender(5L, "agent", null)).thenReturn(senderContext(sessionWith(1_000_000, 10), "agente.teste"));
        when(extensionRepository.findByExtensionIgnoreCaseAndActiveTrue("csv"))
                .thenReturn(Optional.of(CcChatAttachmentExtension.builder().extension("csv").active(true).build()));
        when(attachmentRepository.sumSizeByUploaderSince(anyString(), any(LocalDateTime.class))).thenReturn(0L);
        var file = new MockMultipartFile("file", "dados.csv", "text/csv", "a,b,c".getBytes());

        var attachment = service.upload(5L, "agent", file);

        assertThat(attachment.getSizeBytes()).isEqualTo(5L);
        assertThat(attachment.getUploaderKey()).isEqualTo("agente.teste");
        // Ponto é removido do nome de diretório pelo sanitizador (allowlist alfanumérico/_/-),
        // mesmo que sobreviva na uploaderKey persistida — o diretório físico é sempre mais estrito.
        assertThat(attachment.getStoredRelativePath()).startsWith("agente_teste/");
        assertThat(tempDir.resolve(attachment.getStoredRelativePath())).exists();
    }

    @Test
    @DisplayName("upload sanitiza o nome do diretório do uploader (nunca interpola cru no caminho)")
    void upload_sanitizesUploaderDirectory() {
        when(chatService.validateSender(5L, "customer", null))
                .thenReturn(senderContext(sessionWith(1_000_000, 10), "cliente-../../etc"));
        when(extensionRepository.findByExtensionIgnoreCaseAndActiveTrue("csv"))
                .thenReturn(Optional.of(CcChatAttachmentExtension.builder().extension("csv").active(true).build()));
        when(attachmentRepository.sumSizeByUploaderSince(anyString(), any(LocalDateTime.class))).thenReturn(0L);
        var file = new MockMultipartFile("file", "dados.csv", "text/csv", "a".getBytes());

        var attachment = service.upload(5L, "customer", file);

        assertThat(attachment.getStoredRelativePath()).doesNotContain("..");
        assertThat(tempDir.resolve(attachment.getStoredRelativePath())).exists();
    }
}
