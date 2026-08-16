package com.asteriskia.domain.callcenter.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * ChatTranscriptExportService — exporta o transcript de uma sessão de chat encerrada para
 * {@code /opt/VoipIA/media/chat/YYYY/MM/DD/<sessionId>.json} + {@code .txt} (Fase 11 do plano
 * omnicanal — D6, transcript textual; caminho padronizado na Fase 20 do plano Parte III; o
 * co-browsing gravado é a Fase 17, separada).
 *
 * <p>Classe separada de {@link CcChatService} pelo mesmo motivo do {@code AuditWriter}: um
 * método {@code @Async} chamado por auto-invocação (this.export(...)) não passaria pelo proxy do
 * Spring e rodaria síncrono, sem querer.
 *
 * <p>Disparado por {@link CcChatService#close} via {@code TransactionSynchronization#afterCommit}
 * — só depois que a sessão "closed" já está persistida, para nunca sobrescrever com um snapshot
 * carregado antes do commit (Hibernate por padrão gera UPDATE com todas as colunas mapeadas, não
 * só as alteradas — carregar a entidade de novo, já pós-commit, evita esse risco de corrida).
 *
 * <p>Nunca lança: falha de disco/serialização é logada e a sessão fica com
 * {@code transcriptPath = null} — não impede o encerramento nem quebra o fluxo do agente. Mesma
 * disciplina de "nunca derruba a chamada" do dialplan de gravação de voz (Fase 3).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatTranscriptExportService {

    private static final DateTimeFormatter DIR_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final CcChatSessionRepository sessionRepository;
    private final CcChatMessageRepository messageRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.callcenter.chat-transcript-path:/opt/VoipIA/media/chat}")
    private String transcriptBasePath;

    @Async
    public void export(Long sessionId) {
        try {
            CcChatSession session = sessionRepository.findById(sessionId).orElse(null);
            if (session == null) {
                log.warn("Sessão de chat não encontrada para exportar transcript: id={}", sessionId);
                return;
            }

            List<CcChatMessage> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);

            Path dir = Path.of(transcriptBasePath, DIR_FORMAT.format(session.getStartedAt()));
            Files.createDirectories(dir);

            Path jsonPath = dir.resolve(sessionId + ".json");
            Path txtPath = dir.resolve(sessionId + ".txt");

            writeJson(jsonPath, session, messages);
            writeText(txtPath, session, messages);

            // Grava só o .json — é o caminho canônico; o .txt é derivado no mesmo diretório com
            // a mesma raiz de nome, igual à convenção {uuid}.wav/{uuid}.json da gravação de voz.
            updateTranscriptPath(sessionId, jsonPath.toString());
            log.info("Transcript de chat exportado: sessionId={} path={}", sessionId, jsonPath);
        } catch (Exception e) {
            // Exportação é best-effort — nunca deve propagar para quem encerrou a sessão.
            log.error("Erro ao exportar transcript da sessão de chat id={}: {}", sessionId, e.getMessage(), e);
        }
    }

    private void writeJson(Path path, CcChatSession session, List<CcChatMessage> messages) throws IOException {
        Map<String, Object> payload = Map.of(
                "sessionId", session.getId(),
                "channel", session.getChannel().getCode(),
                "queue", session.getQueue().getName(),
                "customerRef", session.getCustomerRef(),
                "customerName", session.getCustomerName() != null ? session.getCustomerName() : "",
                "startedAt", session.getStartedAt().toString(),
                "closedAt", session.getClosedAt() != null ? session.getClosedAt().toString() : "",
                "disposition", session.getDisposition() != null ? session.getDisposition().getLabel() : "",
                "messages", messages.stream().map(this::toJsonMessage).toList());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), payload);
    }

    private Map<String, Object> toJsonMessage(CcChatMessage message) {
        return Map.of(
                "senderType", message.getSenderType(),
                "senderName", message.getSenderName() != null ? message.getSenderName() : "",
                "body", message.getBody(),
                "createdAt", message.getCreatedAt().toString());
    }

    private void writeText(Path path, CcChatSession session, List<CcChatMessage> messages) throws IOException {
        String customerLabel = session.getCustomerName() != null ? session.getCustomerName() : session.getCustomerRef();
        StringBuilder sb = new StringBuilder();
        sb.append("Sessão de chat #").append(session.getId()).append('\n');
        sb.append("Fila: ").append(session.getQueue().getName()).append('\n');
        sb.append("Cliente: ").append(customerLabel).append('\n');
        sb.append("Início: ").append(session.getStartedAt()).append('\n');
        if (session.getClosedAt() != null) {
            sb.append("Encerramento: ").append(session.getClosedAt()).append('\n');
        }
        sb.append("----\n");
        for (CcChatMessage message : messages) {
            sb.append('[').append(message.getCreatedAt()).append("] ")
                    .append(message.getSenderName() != null ? message.getSenderName() : message.getSenderType())
                    .append(": ").append(message.getBody()).append('\n');
        }
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    private void updateTranscriptPath(Long sessionId, String path) {
        sessionRepository.findById(sessionId).ifPresent(session -> {
            session.setTranscriptPath(path);
            sessionRepository.save(session);
        });
    }
}
