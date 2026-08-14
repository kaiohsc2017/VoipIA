package com.asteriskia.domain.callcenter.flow.audio;

import com.asteriskia.domain.masterdata.BusinessUnitRepository;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * CallCenterAudioService — biblioteca de áudios do Flow Builder (Fase 5c). Upload sempre
 * transcodificado para PCM 8kHz/16-bit mono via {@code ffmpeg} (mesmo binário já usado em
 * {@code InsightsController}/{@code CallCenterRecordingController}) — o arquivo original enviado
 * NUNCA é mantido nem servido ao Asterisk. Se a conversão falhar (arquivo corrompido, não é
 * áudio), o upload é rejeitado e nada fica em disco — nem o original, nem parcial (acréscimo do
 * add.txt sobre o escopo original da Fase 5c).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallCenterAudioService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("wav", "mp3", "ogg", "m4a", "gsm", "flac");
    private static final long MAX_FILE_SIZE_BYTES = 20L * 1024 * 1024;

    // Fase 10, achado MEDIUM: cada upload dispara um processo ffmpeg (até 30s) sem limite —
    // N uploads simultâneos = N processos competindo por CPU no container backend (que também
    // hospeda o listener ARI/AMI e os schedulers do Call Center). Teto de concorrência, não de
    // fila — upload que não consegue permit em tempo curto falha explicitamente (429), nunca
    // enfileira indefinidamente.
    private static final int MAX_CONCURRENT_TRANSCODES = 3;
    private static final long TRANSCODE_PERMIT_WAIT_SECONDS = 5;
    private final Semaphore transcodeSemaphore = new Semaphore(MAX_CONCURRENT_TRANSCODES);

    private final CcAudioFileRepository audioFileRepository;
    private final BusinessUnitRepository businessUnitRepository;

    @Value("${app.callcenter.audio-library-path:/opt/AsteriskIA/media/anuncios}")
    private String audioLibraryPath;

    // Sem @Transactional: o transcode via ffmpeg/ffprobe (subprocessos bloqueantes, até ~30s)
    // roda inteiro antes do único save() — prender uma conexão do pool pelo tempo do subprocesso
    // é a mesma classe de bug já corrigida na Fase 21 (CallCenterSurveyRunner/
    // CallCenterNpsTranscriptionScheduler). audioFileRepository.save já é transacional por conta
    // própria via Spring Data.
    public CcAudioFileDto upload(MultipartFile file, String displayName, Long businessUnitId, String uploadedBy) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Nenhum arquivo enviado.");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Arquivo excede 20MB.");
        }
        String extension = extensionOf(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Extensão não permitida — aceitos: " + ALLOWED_EXTENSIONS);
        }

        File libraryDir = new File(audioLibraryPath).getAbsoluteFile();
        if (!libraryDir.exists() && !libraryDir.mkdirs()) {
            throw new IllegalStateException("Falha ao criar diretório da biblioteca de áudios: " + libraryDir);
        }

        String fileName = "audio-" + UUID.randomUUID();
        Path tempOriginal = null;
        Path finalTarget = libraryDir.toPath().resolve(fileName + ".wav").normalize();
        if (!finalTarget.startsWith(libraryDir.toPath())) {
            throw new IllegalStateException("Nome de arquivo inválido gerado para o upload.");
        }
        boolean acquired = false;
        try {
            tempOriginal = Files.createTempFile("cc-audio-upload-", "." + extension);
            file.transferTo(tempOriginal);

            try {
                acquired = transcodeSemaphore.tryAcquire(TRANSCODE_PERMIT_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Upload interrompido, tente novamente.");
            }
            if (!acquired) {
                throw new ResponseStatusException(
                        org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                        "Muitos uploads de áudio em processamento — tente novamente em alguns segundos.");
            }

            boolean converted = transcodeToPcm8kMono(tempOriginal, finalTarget);
            if (!converted) {
                Files.deleteIfExists(finalTarget);
                throw new ResponseStatusException(
                        org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                        "Não foi possível converter o arquivo enviado — verifique se é um áudio válido.");
            }

            Integer durationSeconds = probeDurationSeconds(finalTarget);
            var businessUnit =
                    businessUnitId == null ? null : businessUnitRepository.findById(businessUnitId.intValue()).orElse(null);
            var resolvedName = displayName == null || displayName.isBlank() ? file.getOriginalFilename() : displayName;
            var entity =
                    CcAudioFile.builder()
                            .name(truncate(resolvedName, 150))
                            .fileName(fileName)
                            .format("wav")
                            .durationSeconds(durationSeconds)
                            .businessUnit(businessUnit)
                            .uploadedBy(uploadedBy)
                            .build();
            var saved = audioFileRepository.save(entity);
            log.info("Áudio da biblioteca do Flow Builder criado: id={} fileName={} uploadedBy={}", saved.getId(), fileName, uploadedBy);
            return CcAudioFileDto.from(saved);
        } catch (IOException e) {
            deleteQuietly(finalTarget);
            throw new IllegalStateException("Falha ao processar upload de áudio.", e);
        } finally {
            if (acquired) {
                transcodeSemaphore.release();
            }
            if (tempOriginal != null) {
                deleteQuietly(tempOriginal);
            }
        }
    }

    public List<CcAudioFileDto> list() {
        return audioFileRepository.findAllByOrderByNameAsc().stream().map(CcAudioFileDto::from).toList();
    }

    @Transactional
    public void delete(Long id) {
        var entity =
                audioFileRepository
                        .findById(id)
                        .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        audioFileRepository.delete(entity);
        deleteQuietly(new File(audioLibraryPath, entity.getFileName() + ".wav").toPath());
    }

    /** Resolve o {@code id} da biblioteca para o caminho {@code sound:} que o ARI entende. {@code
     * Optional.empty()} se o id não existir — quem chama não deve travar a chamada por isso. */
    public java.util.Optional<String> resolveSoundPath(Long audioFileId) {
        return audioFileRepository.findById(audioFileId).map(a -> "asteriskia/" + a.getFileName());
    }

    /** Arquivo físico para pré-escuta no editor (já PCM wav — nenhuma transcodificação
     * necessária pra tocar no navegador). {@code fileName} é sempre gerado pelo próprio serviço
     * (nunca vem de entrada do usuário), então não há risco de path traversal aqui. */
    public java.util.Optional<File> resolveFile(Long audioFileId) {
        return audioFileRepository
                .findById(audioFileId)
                .map(a -> new File(audioLibraryPath, a.getFileName() + ".wav"))
                .filter(File::isFile);
    }

    private boolean transcodeToPcm8kMono(Path source, Path target) {
        try {
            Process ffmpeg =
                    new ProcessBuilder(
                                    "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                                    "-i", source.toAbsolutePath().toString(),
                                    "-ar", "8000", "-ac", "1", "-sample_fmt", "s16",
                                    target.toAbsolutePath().toString())
                            .redirectErrorStream(true)
                            .start();
            boolean finished = ffmpeg.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                ffmpeg.destroyForcibly();
                return false;
            }
            return ffmpeg.exitValue() == 0 && Files.exists(target) && Files.size(target) > 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Falha ao transcodificar upload de áudio: {}", e.getMessage());
            return false;
        }
    }

    private Integer probeDurationSeconds(Path wavFile) {
        try {
            Process ffprobe =
                    new ProcessBuilder(
                                    "ffprobe", "-v", "error", "-show_entries", "format=duration", "-of",
                                    "default=noprint_wrappers=1:nokey=1", wavFile.toAbsolutePath().toString())
                            .start();
            String output;
            try (var in = ffprobe.getInputStream()) {
                output = new String(in.readAllBytes()).trim();
            }
            boolean finished = ffprobe.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                ffprobe.destroyForcibly();
                return null;
            }
            return (int) Math.round(Double.parseDouble(output));
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String value, int maxLength) {
        var safe = value == null || value.isBlank() ? "audio" : value;
        return safe.length() > maxLength ? safe.substring(0, maxLength) : safe;
    }

    private String extensionOf(String originalFilename) {
        if (originalFilename == null) return "";
        int dot = originalFilename.lastIndexOf('.');
        return dot >= 0 ? originalFilename.substring(dot + 1).toLowerCase() : "";
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("Falha ao remover arquivo temporário/parcial {}: {}", path, e.getMessage());
        }
    }
}
