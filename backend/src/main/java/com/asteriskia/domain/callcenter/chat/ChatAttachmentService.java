package com.asteriskia.domain.callcenter.chat;

import com.asteriskia.config.ResourceNotFoundException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * ChatAttachmentService — upload/listagem/download de anexos no chat (Fase 7d), bidirecional
 * (agente e cliente, D6). Reusa {@link CcChatService#validateSender} para a mesma regra de
 * posse/status já aplicada a mensagens de texto — nunca duplicada.
 *
 * <p>Validação em duas camadas antes de gravar qualquer coisa em disco: (1) extensão precisa
 * estar cadastrada e ativa em {@link CcChatAttachmentExtensionRepository}; (2) quando a extensão
 * tem uma assinatura de magic-bytes conhecida (ver {@link #MAGIC_BYTES}), os primeiros bytes do
 * arquivo precisam bater — nunca confia só no nome. Extensões cadastradas fora desse mapa (o
 * operador pode cadastrar qualquer uma) pulam a checagem de magic-bytes, gap aceito e documentado
 * — o nome ainda passa pela allowlist.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAttachmentService {

    private static final long MAX_FILE_SIZE_BYTES = 25L * 1024 * 1024;

    /** Assinaturas conhecidas (magic-bytes) — só para os formatos mais comuns; extensão fora
     * deste mapa pula a checagem (documentado na classe). */
    private static final Map<String, byte[]> MAGIC_BYTES = Map.of(
            "png", new byte[] {(byte) 0x89, 'P', 'N', 'G'},
            "jpg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF},
            "jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF},
            "gif", new byte[] {'G', 'I', 'F', '8'},
            "pdf", new byte[] {'%', 'P', 'D', 'F'},
            "zip", new byte[] {'P', 'K', 0x03, 0x04},
            // docx/xlsx/pptx são zip por dentro — mesma assinatura do zip.
            "docx", new byte[] {'P', 'K', 0x03, 0x04},
            "xlsx", new byte[] {'P', 'K', 0x03, 0x04},
            "pptx", new byte[] {'P', 'K', 0x03, 0x04});

    private final CcChatService chatService;
    private final CcChatAttachmentRepository attachmentRepository;
    private final CcChatAttachmentExtensionRepository extensionRepository;
    private final SimpMessagingTemplate messagingTemplate;

    @Value("${app.callcenter.chat-attachment-path:/opt/VoipIA/media/chat/anexos}")
    private String basePath;

    @Transactional
    public CcChatAttachment upload(Long sessionId, String senderType, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Nenhum arquivo enviado.");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Arquivo excede o tamanho máximo de 25MB.");
        }

        var ctx = chatService.validateSender(sessionId, senderType, null);
        var session = ctx.session();

        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "arquivo";
        String extension = extensionOf(originalName);
        var allowed = extensionRepository.findByExtensionIgnoreCaseAndActiveTrue(extension)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Extensão \"" + extension + "\" não é aceita para anexo neste chat."));
        validateMagicBytes(file, extension);

        var channel = session.getChannel();
        long quotaBytes = channel.getAttachmentQuotaBytes();
        int retentionDays = channel.getAttachmentRetentionDays();
        var sinceInstant = LocalDateTime.now().minusDays(retentionDays);
        long currentUsage = attachmentRepository.sumSizeByUploaderSince(ctx.uploaderKey(), sinceInstant);
        if (currentUsage + file.getSize() > quotaBytes) {
            long remaining = Math.max(0, quotaBytes - currentUsage);
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Cota de anexos deste perfil excedida — faltam " + remaining
                            + " bytes disponíveis (cota total: " + quotaBytes + " bytes).");
        }

        String storedFileName = UUID.randomUUID() + "_" + sanitizeFileName(originalName);
        String sanitizedUploaderDir = sanitizeDirName(ctx.uploaderKey());
        Path uploaderDir = new File(new File(basePath).getAbsoluteFile(), sanitizedUploaderDir).toPath();
        try {
            Files.createDirectories(uploaderDir);
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao criar diretório de anexos para " + sanitizedUploaderDir, e);
        }
        Path target = uploaderDir.resolve(storedFileName).normalize();
        if (!target.startsWith(uploaderDir)) {
            // Cinturão de segurança contra traversal — sanitizeFileName já remove separadores,
            // mas nunca gravamos sem confirmar de novo (mesmo princípio de InsightsUploadService).
            throw new IllegalStateException("Nome de arquivo inválido: " + originalName);
        }
        try {
            file.transferTo(target);
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao salvar anexo '" + originalName + "'", e);
        }

        var attachment = attachmentRepository.save(CcChatAttachment.builder()
                .sessionId(sessionId)
                .senderType(senderType)
                .senderName(ctx.resolvedSenderName())
                .uploaderKey(ctx.uploaderKey())
                .originalFileName(originalName)
                .storedRelativePath(sanitizedUploaderDir + "/" + storedFileName)
                .contentType(allowed.getMimetype() != null ? allowed.getMimetype() : file.getContentType())
                .sizeBytes(file.getSize())
                .build());

        messagingTemplate.convertAndSend("/topic/callcenter/chat/session/" + sessionId,
                new CcChatService.ChatQueueEvent(sessionId, null, null));
        log.info("Anexo enviado: sessionId={} uploaderKey={} arquivo={} bytes={}",
                sessionId, ctx.uploaderKey(), originalName, file.getSize());
        return attachment;
    }

    @Transactional(readOnly = true)
    public List<CcChatAttachment> list(Long sessionId) {
        return attachmentRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    /** Resolve o caminho absoluto no disco de um anexo já validado como pertencente à sessão
     * informada (o chamador — controller de staff ou público — já garantiu a posse). */
    public File resolveFile(CcChatAttachment attachment) {
        Path resolved = new File(new File(basePath).getAbsoluteFile(), attachment.getStoredRelativePath()).toPath().normalize();
        Path base = new File(basePath).getAbsoluteFile().toPath().normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalStateException("Caminho de anexo fora da raiz esperada: " + attachment.getStoredRelativePath());
        }
        return resolved.toFile();
    }

    @Transactional(readOnly = true)
    public CcChatAttachment findByIdOrThrow(Long id) {
        return attachmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Anexo não encontrado: " + id));
    }

    private void validateMagicBytes(MultipartFile file, String extension) {
        byte[] signature = MAGIC_BYTES.get(extension.toLowerCase());
        if (signature == null) {
            return; // extensão fora do mapa conhecido — gap aceito, documentado na classe.
        }
        try (var in = file.getInputStream()) {
            byte[] head = in.readNBytes(signature.length);
            for (int i = 0; i < signature.length; i++) {
                if (i >= head.length || head[i] != signature[i]) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Conteúdo do arquivo não corresponde à extensão \"" + extension + "\".");
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao ler o arquivo enviado para validação.", e);
        }
    }

    private String extensionOf(String originalFilename) {
        int dot = originalFilename.lastIndexOf('.');
        return dot >= 0 ? originalFilename.substring(dot + 1).toLowerCase() : "";
    }

    /** Mesmo princípio de {@code InsightsUploadService.sanitizeFileName} — nome do usuário nunca
     * é usado cru num caminho de disco. */
    private String sanitizeFileName(String originalFilename) {
        String base = new File(originalFilename).getName();
        String cleaned = base.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isBlank() ? "arquivo" : cleaned;
    }

    /** Nome de diretório por uploader — mesma allowlist de {@link #sanitizeFileName}, sem ponto
     * (evita "..") e limitado a um tamanho razoável (nome de usuário/customerRef nunca deveria
     * ser gigante, mas nunca confiamos nisso). */
    private String sanitizeDirName(String uploaderKey) {
        String cleaned = uploaderKey.replaceAll("[^A-Za-z0-9_-]", "_");
        if (cleaned.isBlank()) {
            cleaned = "desconhecido";
        }
        return cleaned.length() > 100 ? cleaned.substring(0, 100) : cleaned;
    }
}
