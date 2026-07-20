package com.asteriskia.domain.insights;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * InsightsUploadService — portal do supervisor: upload em lote de áudios para
 * transcrição/análise ad-hoc (Fase 3 do Quality Management, V40). Reusa o mesmo
 * pipeline de STT/análise/avaliação do fluxo Verint (o serviço asteriskia-insights
 * processa qualquer CallAudioFile pendente, independente de source) — este service só
 * cuida do recebimento/validação do arquivo e do registro inicial.
 *
 * Posse: supervisor só vê os próprios lotes (uploadedBy=username); ADMIN vê todos.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InsightsUploadService {

    private static final int MAX_FILES_PER_BATCH = 100;
    private static final long MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("wav", "mp3", "ogg", "m4a");

    private final UploadBatchRepository batchRepository;
    private final CallAudioFileRepository audioFileRepository;
    private final InsightsIngestionService ingestionService;

    @Value("${app.insights.upload-audio-path:/opt/audio_upload}")
    private String uploadBasePath;

    @Transactional
    public UploadBatchDto createBatch(List<MultipartFile> files, String agentName, String direction,
                                       String notes, String uploadedBy) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("Nenhum arquivo enviado.");
        }
        if (files.size() > MAX_FILES_PER_BATCH) {
            throw new IllegalArgumentException("Máximo de " + MAX_FILES_PER_BATCH + " arquivos por lote (recebidos: " + files.size() + ").");
        }
        for (MultipartFile file : files) {
            if (file.getSize() > MAX_FILE_SIZE_BYTES) {
                throw new IllegalArgumentException("Arquivo '" + file.getOriginalFilename() + "' excede 50MB.");
            }
            String extension = extensionOf(file.getOriginalFilename());
            if (!ALLOWED_EXTENSIONS.contains(extension)) {
                throw new IllegalArgumentException("Extensão não permitida em '" + file.getOriginalFilename()
                        + "' — aceitos: " + ALLOWED_EXTENSIONS);
            }
        }

        UUID batchId = UUID.randomUUID();
        UploadBatch batch = batchRepository.save(UploadBatch.builder()
                .id(batchId)
                .uploadedBy(uploadedBy)
                .fileCount(files.size())
                .notes(notes)
                .build());

        File batchDir = new File(new File(uploadBasePath).getAbsoluteFile(), batchId.toString());
        if (!batchDir.mkdirs() && !batchDir.isDirectory()) {
            throw new IllegalStateException("Falha ao criar diretório do lote de upload: " + batchDir);
        }

        int seq = 0;
        for (MultipartFile file : files) {
            seq++;
            String sanitized = sanitizeFileName(file.getOriginalFilename(), seq);
            Path target = batchDir.toPath().resolve(sanitized).normalize();
            if (!target.startsWith(batchDir.toPath())) {
                // Nunca deveria acontecer (sanitizeFileName já remove separadores de path),
                // mas é um cinturão de segurança contra traversal antes de gravar em disco.
                throw new IllegalStateException("Nome de arquivo inválido: " + file.getOriginalFilename());
            }
            try {
                file.transferTo(target);
            } catch (IOException e) {
                throw new IllegalStateException("Falha ao salvar arquivo '" + file.getOriginalFilename() + "'", e);
            }

            String callRef = "up-" + batchId + "-" + seq;
            ingestionService.registerUpload(callRef, target.toAbsolutePath().toString(), agentName, direction, uploadedBy, batchId);
        }

        log.info("Lote de upload criado: id={} uploadedBy={} arquivos={}", batchId, uploadedBy, files.size());
        return UploadBatchDto.summary(batch);
    }

    public Page<UploadBatchDto> listBatches(String uploadedBy, boolean isAdmin, Pageable pageable) {
        Page<UploadBatch> page = isAdmin
                ? batchRepository.findAllByOrderByCreatedAtDesc(pageable)
                : batchRepository.findByUploadedByOrderByCreatedAtDesc(uploadedBy, pageable);
        return page.map(UploadBatchDto::summary);
    }

    /** 404 (não 403) para lote alheio — não vaza existência a quem não é dono nem ADMIN. */
    public Optional<UploadBatchDto> batchDetail(UUID batchId, String uploadedBy, boolean isAdmin) {
        return batchRepository.findById(batchId)
                .filter(b -> isAdmin || b.getUploadedBy().equals(uploadedBy))
                .map(batch -> {
                    List<UploadBatchDto.UploadFileSummary> files = audioFileRepository
                            .findByUploadBatchIdOrderByIdAsc(batchId).stream()
                            .map(UploadBatchDto.UploadFileSummary::from)
                            .toList();
                    return UploadBatchDto.detail(batch, files);
                });
    }

    private String extensionOf(String originalFilename) {
        if (originalFilename == null) return "";
        int dot = originalFilename.lastIndexOf('.');
        return dot >= 0 ? originalFilename.substring(dot + 1).toLowerCase() : "";
    }

    /** Remove qualquer separador de diretório e caractere fora de um allowlist simples —
     * nome de arquivo do usuário nunca é usado cru num caminho de disco (mesmo princípio
     * de resolveWithinBase em InsightsController). */
    private String sanitizeFileName(String originalFilename, int seq) {
        String base = originalFilename != null ? new File(originalFilename).getName() : "arquivo";
        String cleaned = base.replaceAll("[^A-Za-z0-9._-]", "_");
        if (cleaned.isBlank()) {
            cleaned = "arquivo";
        }
        return seq + "_" + cleaned;
    }
}
