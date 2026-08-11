package com.asteriskia.domain.callcenter.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.callcenter.CcQueue;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Cobre a exportação de transcript de chat da Fase 11 do plano omnicanal (D6 — transcript
 * textual; o co-browsing gravado é a Fase 17, separada). Foco: nunca lançar exceção pro chamador
 * (é {@code @Async} e best-effort), gravar os dois arquivos com o conteúdo esperado, e atualizar
 * {@code transcriptPath} só quando a exportação der certo.
 */
@ExtendWith(MockitoExtension.class)
class ChatTranscriptExportServiceTest {

    @Mock
    private CcChatSessionRepository sessionRepository;
    @Mock
    private CcChatMessageRepository messageRepository;

    private ChatTranscriptExportService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        service = new ChatTranscriptExportService(sessionRepository, messageRepository, new ObjectMapper());
        ReflectionTestUtils.setField(service, "transcriptBasePath", tempDir.toString());
    }

    private CcChatSession sessionOf(Long id, LocalDateTime startedAt) {
        CcChatChannel channel = new CcChatChannel();
        channel.setCode("internal_test");
        CcQueue queue = CcQueue.builder().id(10L).name("suporte").build();
        return CcChatSession.builder()
                .id(id)
                .channel(channel)
                .queue(queue)
                .customerRef("cliente-1")
                .customerName("Maria")
                .status("closed")
                .startedAt(startedAt)
                .closedAt(startedAt.plusMinutes(5))
                .build();
    }

    private CcChatMessage messageOf(Long sessionId, String senderType, String body) {
        CcChatMessage message = CcChatMessage.builder()
                .sessionId(sessionId)
                .senderType(senderType)
                .senderName(senderType.equals("agent") ? "Agente Um" : "Maria")
                .body(body)
                .build();
        message.setCreatedAt(LocalDateTime.now());
        return message;
    }

    @Test
    @DisplayName("exporta JSON e TXT no diretório yyyy/MM/dd derivado de startedAt e grava transcriptPath")
    void export_success_writesFilesAndUpdatesPath() throws IOException {
        LocalDateTime startedAt = LocalDateTime.of(2026, 8, 8, 10, 0);
        CcChatSession session = sessionOf(5L, startedAt);
        List<CcChatMessage> messages = List.of(
                messageOf(5L, "customer", "Olá, preciso de ajuda"),
                messageOf(5L, "agent", "Claro, em que posso ajudar?"));

        when(sessionRepository.findById(5L)).thenReturn(Optional.of(session), Optional.of(session));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(5L)).thenReturn(messages);
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.export(5L);

        Path expectedDir = tempDir.resolve("2026/08/08");
        Path jsonPath = expectedDir.resolve("5.json");
        Path txtPath = expectedDir.resolve("5.txt");

        assertThat(Files.exists(jsonPath)).isTrue();
        assertThat(Files.exists(txtPath)).isTrue();

        String json = Files.readString(jsonPath);
        assertThat(json).contains("\"customerName\"").contains("Maria").contains("Olá, preciso de ajuda");

        String txt = Files.readString(txtPath);
        assertThat(txt).contains("Sessão de chat #5").contains("Claro, em que posso ajudar?");

        assertThat(session.getTranscriptPath()).isEqualTo(jsonPath.toString());
        verify(sessionRepository).save(session);
    }

    @Test
    @DisplayName("sessão inexistente não lança exceção e não grava nada")
    void export_sessionNotFound_doesNothingAndNeverThrows() {
        when(sessionRepository.findById(99L)).thenReturn(Optional.empty());

        service.export(99L);

        verify(sessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("falha de I/O é engolida — nunca propaga para o chamador")
    void export_ioFailure_neverThrows() {
        LocalDateTime startedAt = LocalDateTime.of(2026, 8, 8, 10, 0);
        CcChatSession session = sessionOf(5L, startedAt);
        when(sessionRepository.findById(5L)).thenReturn(Optional.of(session));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(5L)).thenReturn(List.of());
        // Caminho base inválido (arquivo comum, não diretório) força IOException em createDirectories.
        ReflectionTestUtils.setField(service, "transcriptBasePath", "/dev/null/impossivel");

        service.export(5L);

        verify(sessionRepository, never()).save(any());
    }
}
